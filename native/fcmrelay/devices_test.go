package main

import (
	"context"
	"crypto"
	"crypto/ecdh"
	"crypto/hkdf"
	"crypto/hmac"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"net/http"
	"net/http/httptest"
	"sort"
	"strings"
	"sync"
	"testing"
	"time"
)

type memStore struct {
	mu      sync.Mutex
	devices map[string]*DeviceRecord
	state   map[string]*State
	version map[string]int
	failing bool
}

func (m *memStore) Device(_ context.Context, owner, id string) (*DeviceRecord, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.devices[owner+"/"+id], nil
}

func (m *memStore) LoadState(_ context.Context, owner string) (*State, string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	stored := m.state[owner]
	if stored == nil {
		return nil, "", nil
	}
	copied, _ := json.Marshal(stored)
	var out State
	json.Unmarshal(copied, &out)
	return &out, string(rune('0' + m.version[owner])), nil
}

func (m *memStore) SaveState(_ context.Context, owner string, state *State, version string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	current := ""
	if m.state[owner] != nil {
		current = string(rune('0' + m.version[owner]))
	}
	if version != current || m.failing {
		return errConflict
	}
	copied, _ := json.Marshal(state)
	var stored State
	json.Unmarshal(copied, &stored)
	m.state[owner] = &stored
	m.version[owner]++
	return nil
}

type memIdentity struct {
	mu      sync.Mutex
	enabled map[string]bool
	failing bool
}

func (m *memIdentity) SetEnabled(_ context.Context, uid string, enabled bool) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.failing {
		return errors.New("identity down")
	}
	m.enabled[uid] = enabled
	return nil
}

type testDevice struct {
	id      string
	hash    string
	private *ecdh.PrivateKey
	public  string
}

func newTestDevice(t *testing.T) testDevice {
	t.Helper()
	private, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	public := base64.StdEncoding.EncodeToString(private.PublicKey().Bytes())
	digest, _ := keyDigestHex(public)
	return testDevice{id: "fx-" + digest[:32], hash: digest[:16], private: private, public: public}
}

func (d testDevice) enrolBody(t *testing.T, relayKey string, now time.Time, nonce string) string {
	t.Helper()
	raw, _ := base64.StdEncoding.DecodeString(relayKey)
	relay, err := ecdh.X25519().NewPublicKey(raw)
	if err != nil {
		t.Fatal(err)
	}
	shared, _ := d.private.ECDH(relay)
	ids := []string{d.id, proofRelayID}
	sort.Strings(ids)
	key, _ := hkdf.Key(sha256.New, shared, []byte(strings.Join(ids, "|")), proofHKDFInfo, 32)
	mac := hmac.New(sha256.New, key)
	mac.Write(proofMessage(d.id, now.Unix(), nonce))
	body, _ := json.Marshal(enrolRequest{
		DeviceID: d.id, Ts: now.Unix(), Nonce: nonce,
		Tag: base64.StdEncoding.EncodeToString(mac.Sum(nil)),
	})
	return string(body)
}

type deviceRig struct {
	server   *httptest.Server
	store    *memStore
	identity *memIdentity
	admin    *DeviceAdmin
	idKey    *rsa.PrivateKey
	svcKey   *rsa.PublicKey
}

func newDeviceRig(t *testing.T) *deviceRig {
	t.Helper()
	idKey := newKey(t)
	svc := newKey(t)
	pkcs8, _ := x509.MarshalPKCS8PrivateKey(svc)
	account := ServiceAccount{ProjectID: "proj", ClientEmail: "svc@proj.iam", PrivateKey: string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: pkcs8}))}
	proof, err := newProofKey(fixedNow)
	if err != nil {
		t.Fatal(err)
	}
	rig := &deviceRig{
		store:    &memStore{devices: map[string]*DeviceRecord{}, state: map[string]*State{}, version: map[string]int{}},
		identity: &memIdentity{enabled: map[string]bool{}},
		idKey:    idKey,
		svcKey:   &svc.PublicKey,
	}
	rig.admin = &DeviceAdmin{Store: rig.store, Identity: rig.identity, Tokens: &CustomTokens{Account: account, Now: fixedNow}, Proof: proof, Now: fixedNow}
	server := &Server{
		Verifier: &Verifier{ProjectID: "proj", Keys: fixedKeys{"k1": &idKey.PublicKey}, Now: fixedNow},
		Sender:   &fakeSender{},
		PerUser:  newLimiter(600, 100, fixedNow),
		Global:   newLimiter(6000, 1000, fixedNow),
		Devices:  rig.admin,
	}
	rig.server = httptest.NewServer(server.Handler())
	t.Cleanup(rig.server.Close)
	return rig
}

func (r *deviceRig) idToken(t *testing.T, sub string) string {
	claims := goodClaims()
	claims["sub"] = sub
	return signedToken(t, r.idKey, "k1", claims)
}

func (r *deviceRig) call(t *testing.T, path, token, body string) (int, map[string]string) {
	t.Helper()
	request, _ := http.NewRequest(http.MethodPost, r.server.URL+path, strings.NewReader(body))
	if token != "" {
		request.Header.Set("Authorization", "Bearer "+token)
	}
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	out := map[string]string{}
	json.NewDecoder(response.Body).Decode(&out)
	return response.StatusCode, out
}

func (r *deviceRig) callAny(t *testing.T, path, token, body string) (int, map[string]any) {
	t.Helper()
	request, _ := http.NewRequest(http.MethodPost, r.server.URL+path, strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer "+token)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	out := map[string]any{}
	json.NewDecoder(response.Body).Decode(&out)
	return response.StatusCode, out
}

func (r *deviceRig) relayKey(t *testing.T) string { return r.admin.Proof.PublicBase64() }

func (r *deviceRig) addRecord(owner string, d testDevice, created time.Time) {
	r.store.devices[owner+"/"+d.id] = &DeviceRecord{PublicKeyHash: d.hash, ClipboardPublicKey: d.public, Created: created}
}

var before = testNow.Add(-24 * time.Hour)

func TestEnrolMintsADeviceTokenForAProvenKey(t *testing.T) {
	rig := newDeviceRig(t)
	dev := newTestDevice(t)
	rig.addRecord("user1", dev, before)
	status, out := rig.call(t, "/v1/device/enrol", rig.idToken(t, "user1"), dev.enrolBody(t, rig.relayKey(t), testNow, "nonce-0123456789ab"))
	if status != 200 || out["uid"] != "user1~"+dev.id {
		t.Fatalf("enrol: %d %v", status, out)
	}
	parts := strings.Split(out["token"], ".")
	var claims struct {
		Iss    string         `json:"iss"`
		Aud    string         `json:"aud"`
		UID    string         `json:"uid"`
		Claims map[string]any `json:"claims"`
	}
	if err := decodePart(parts[1], &claims); err != nil {
		t.Fatal(err)
	}
	signature, _ := base64.RawURLEncoding.DecodeString(parts[2])
	digest := sha256.Sum256([]byte(parts[0] + "." + parts[1]))
	if rsa.VerifyPKCS1v15(rig.svcKey, crypto.SHA256, digest[:], signature) != nil {
		t.Error("custom token signature does not verify")
	}
	if claims.UID != "user1~"+dev.id || claims.Aud != identityAudience || claims.Claims["owner"] != "user1" || claims.Claims["deviceId"] != dev.id {
		t.Errorf("claims: %+v", claims)
	}
	if rig.store.state["user1"].Allowed[dev.id] != dev.hash {
		t.Errorf("allow-list: %+v", rig.store.state["user1"])
	}
}

func TestEnrolRefusesBadProofReplayStaleAndWrongOwner(t *testing.T) {
	rig := newDeviceRig(t)
	dev := newTestDevice(t)
	other := newTestDevice(t)
	rig.addRecord("user1", dev, before)
	token := rig.idToken(t, "user1")
	good := dev.enrolBody(t, rig.relayKey(t), testNow, "nonce-0123456789ab")
	if status, _ := rig.call(t, "/v1/device/enrol", token, good); status != 200 {
		t.Fatalf("first enrol %d", status)
	}
	if status, _ := rig.call(t, "/v1/device/enrol", token, good); status != 403 {
		t.Errorf("replay answered %d", status)
	}
	if status, _ := rig.call(t, "/v1/device/enrol", token, dev.enrolBody(t, rig.relayKey(t), testNow.Add(-time.Hour), "nonce-aaaaaaaaaaaa")); status != 403 {
		t.Errorf("stale answered %d", status)
	}
	forged := strings.Replace(dev.enrolBody(t, rig.relayKey(t), testNow, "nonce-bbbbbbbbbbbb"), `"nonce":"nonce-bbbbbbbbbbbb"`, `"nonce":"nonce-cccccccccccc"`, 1)
	if status, _ := rig.call(t, "/v1/device/enrol", token, forged); status != 403 {
		t.Errorf("tampered nonce answered %d", status)
	}
	if status, _ := rig.call(t, "/v1/device/enrol", token, other.enrolBody(t, rig.relayKey(t), testNow, "nonce-dddddddddddd")); status != 404 {
		t.Errorf("device with no record answered %d", status)
	}
	if status, _ := rig.call(t, "/v1/device/enrol", rig.idToken(t, "user2"), dev.enrolBody(t, rig.relayKey(t), testNow, "nonce-eeeeeeeeeeee")); status != 404 {
		t.Errorf("another account asking for this device answered %d", status)
	}
	if status, _ := rig.call(t, "/v1/device/enrol", "", good); status != 401 {
		t.Errorf("no token answered %d", status)
	}
}

func TestEnrolRefusesARecordWhoseKeyDoesNotMatchItsHashOrId(t *testing.T) {
	rig := newDeviceRig(t)
	dev := newTestDevice(t)
	rig.addRecord("user1", dev, before)
	rig.store.devices["user1/"+dev.id].PublicKeyHash = "0000000000000000"
	if status, _ := rig.call(t, "/v1/device/enrol", rig.idToken(t, "user1"), dev.enrolBody(t, rig.relayKey(t), testNow, "nonce-0123456789ab")); status != 403 {
		t.Errorf("hash mismatch answered %d", status)
	}
	rig.addRecord("user1", dev, before)
	rig.store.devices["user1/"+dev.id].ClipboardPublicKey = newTestDevice(t).public
	if status, _ := rig.call(t, "/v1/device/enrol", rig.idToken(t, "user1"), dev.enrolBody(t, rig.relayKey(t), testNow, "nonce-ffffffffffff")); status != 403 {
		t.Errorf("foreign key answered %d", status)
	}
}

func syncBody(admit []admitEntry, revoke []revokeEntry) string {
	body, _ := json.Marshal(syncRequest{Admit: admit, Revoke: revoke})
	return string(body)
}

func admitOf(d testDevice, version int64) admitEntry {
	return admitEntry{DeviceID: d.id, PublicKeyHash: d.hash, Version: version}
}

func TestOnlyRecordsOlderThanTheSeedCutoffEnrolWithoutAnAdmit(t *testing.T) {
	rig := newDeviceRig(t)
	first, late := newTestDevice(t), newTestDevice(t)
	rig.addRecord("user1", first, before)
	if status, _ := rig.call(t, "/v1/device/enrol", rig.idToken(t, "user1"), first.enrolBody(t, rig.relayKey(t), testNow, "nonce-0123456789ab")); status != 200 {
		t.Fatalf("first enrol %d", status)
	}
	rig.addRecord("user1", late, testNow.Add(time.Hour))
	lateToken := rig.idToken(t, "user1")
	if status, _ := rig.call(t, "/v1/device/enrol", lateToken, late.enrolBody(t, rig.relayKey(t), testNow, "nonce-aaaaaaaaaaaa")); status != 403 {
		t.Errorf("record made after the cutoff answered %d", status)
	}
	body := syncBody([]admitEntry{admitOf(late, 10)}, nil)
	if status, _ := rig.call(t, "/v1/device/sync", lateToken, body); status != 403 {
		t.Errorf("plain Google session changed the list: %d", status)
	}
	if status, _ := rig.call(t, "/v1/device/sync", rig.idToken(t, "user1~"+first.id), body); status != 200 {
		t.Fatalf("sync %d", status)
	}
	if status, _ := rig.call(t, "/v1/device/enrol", lateToken, late.enrolBody(t, rig.relayKey(t), testNow, "nonce-bbbbbbbbbbbb")); status != 200 {
		t.Errorf("admitted device answered %d", status)
	}
}

func TestRevokeDeniesAndOnlyANewerAdmitClearsIt(t *testing.T) {
	rig := newDeviceRig(t)
	keeper, gone := newTestDevice(t), newTestDevice(t)
	rig.addRecord("user1", keeper, before)
	rig.addRecord("user1", gone, before)
	legacy := rig.idToken(t, "user1")
	for i, d := range []testDevice{keeper, gone} {
		if status, _ := rig.call(t, "/v1/device/enrol", legacy, d.enrolBody(t, rig.relayKey(t), testNow, "nonce-00000000000"+string(rune('a'+i)))); status != 200 {
			t.Fatalf("enrol %d", status)
		}
	}
	caller := rig.idToken(t, "user1~"+keeper.id)
	revoke := syncBody(nil, []revokeEntry{{DeviceID: gone.id, Version: 100}})
	if status, _ := rig.call(t, "/v1/device/sync", legacy, revoke); status != 403 {
		t.Errorf("a plain Google session revoked: %d", status)
	}
	if status, _ := rig.call(t, "/v1/device/sync", rig.idToken(t, "user2~"+keeper.id), revoke); status != 403 {
		t.Errorf("another account revoked: %d", status)
	}
	if status, _ := rig.call(t, "/v1/device/sync", caller, revoke); status != 200 {
		t.Fatalf("revoke %d", status)
	}
	if enabled, touched := rig.identity.enabled["user1~"+gone.id]; !touched || enabled {
		t.Error("identity was not disabled")
	}
	if status, _ := rig.call(t, "/v1/device/enrol", legacy, gone.enrolBody(t, rig.relayKey(t), testNow, "nonce-zzzzzzzzzzzz")); status != 403 {
		t.Errorf("removed device enrolled again: %d", status)
	}
	_, out := rig.callAny(t, "/v1/device/sync", caller, syncBody([]admitEntry{admitOf(gone, 100)}, nil))
	if out["changed"] != false {
		t.Errorf("an admit at the removal version undid it: %v", out)
	}
	_, out = rig.callAny(t, "/v1/device/sync", caller, syncBody([]admitEntry{admitOf(gone, 200)}, nil))
	if out["changed"] != true || !rig.identity.enabled["user1~"+gone.id] {
		t.Errorf("a newer admit did not clear the removal: %v", out)
	}
	_, out = rig.callAny(t, "/v1/device/sync", caller, syncBody(nil, []revokeEntry{{DeviceID: gone.id, Version: 150}}))
	if out["changed"] != false {
		t.Errorf("a removal older than the re-admit was applied: %v", out)
	}
	if status, _ := rig.call(t, "/v1/device/enrol", legacy, gone.enrolBody(t, rig.relayKey(t), testNow, "nonce-rejoin-0000")); status != 200 {
		t.Errorf("re-admitted device could not enrol: %d", status)
	}
}

func TestAFailedIdentityUpdateLeavesTheStateAlone(t *testing.T) {
	rig := newDeviceRig(t)
	keeper, gone := newTestDevice(t), newTestDevice(t)
	rig.addRecord("user1", keeper, before)
	rig.addRecord("user1", gone, before)
	legacy := rig.idToken(t, "user1")
	rig.call(t, "/v1/device/enrol", legacy, keeper.enrolBody(t, rig.relayKey(t), testNow, "nonce-keeper-00000"))
	rig.call(t, "/v1/device/enrol", legacy, gone.enrolBody(t, rig.relayKey(t), testNow, "nonce-gone-000000"))
	caller := rig.idToken(t, "user1~"+keeper.id)
	revoke := syncBody(nil, []revokeEntry{{DeviceID: gone.id, Version: 100}})
	rig.identity.failing = true
	if status, _ := rig.call(t, "/v1/device/sync", caller, revoke); status != 502 {
		t.Errorf("identity failure answered %d", status)
	}
	if _, listed := rig.store.state["user1"].Allowed[gone.id]; !listed {
		t.Error("state changed although the identity update failed")
	}
	rig.identity.failing = false
	if _, out := rig.callAny(t, "/v1/device/sync", caller, revoke); out["changed"] != true {
		t.Errorf("retry did not apply: %v", out)
	}
}

func TestSimultaneousWritersGetAConflictNotALostUpdate(t *testing.T) {
	rig := newDeviceRig(t)
	dev := newTestDevice(t)
	rig.addRecord("user1", dev, before)
	rig.store.failing = true
	if status, _ := rig.call(t, "/v1/device/enrol", rig.idToken(t, "user1"), dev.enrolBody(t, rig.relayKey(t), testNow, "nonce-0123456789ab")); status != 409 {
		t.Errorf("conflict answered %d", status)
	}
}

func TestDeviceKeyEndpointPublishesTheRelayKey(t *testing.T) {
	rig := newDeviceRig(t)
	response, err := http.Get(rig.server.URL + "/v1/device/key")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var out map[string]string
	json.NewDecoder(response.Body).Decode(&out)
	if out["key"] != rig.relayKey(t) || len(out["key"]) == 0 {
		t.Errorf("key: %v", out)
	}
}

func TestAnOlderRandomIDEnrolsWithItsKeyPinned(t *testing.T) {
	rig := newDeviceRig(t)
	dev := newTestDevice(t)
	dev.id = "77d4304a-ba16-4c8e-a86d-96f884b334a9"
	rig.addRecord("user1", dev, before)
	rig.store.devices["user1/"+dev.id].PublicKeyHash = "fingerprint-not-key"
	token := rig.idToken(t, "user1")
	if status, _ := rig.call(t, "/v1/device/enrol", token, dev.enrolBody(t, rig.relayKey(t), testNow, "nonce-0123456789ab")); status != 200 {
		t.Fatalf("legacy enrol %d", status)
	}
	if rig.store.state["user1"].Allowed[dev.id] != dev.hash {
		t.Errorf("key not pinned: %+v", rig.store.state["user1"])
	}
	other := newTestDevice(t)
	other.id = dev.id
	rig.addRecord("user1", other, before)
	rig.store.devices["user1/"+dev.id].PublicKeyHash = "fingerprint-not-key"
	if status, _ := rig.call(t, "/v1/device/enrol", token, other.enrolBody(t, rig.relayKey(t), testNow, "nonce-ffffffffffff")); status != 403 {
		t.Errorf("re-keyed legacy id answered %d", status)
	}
}
