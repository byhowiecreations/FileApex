package main

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// peerIdentity is a second device: its own TLS identity, pinned by the node under test the way pairing pins it.
type peerIdentity struct {
	id   string
	pin  string
	leaf *x509.Certificate
}

func pinnedPeer(t *testing.T, node *Node, id string) peerIdentity {
	t.Helper()
	state, err := newTLSState(t.TempDir(), time.Now())
	if err != nil {
		t.Fatal(err)
	}
	leaf, err := x509.ParseCertificate(state.identity.cert.Certificate[0])
	if err != nil {
		t.Fatal(err)
	}
	pin, err := leafPin(leaf)
	if err != nil {
		t.Fatal(err)
	}
	node.tls.record(id, pin, 9443)
	return peerIdentity{id: id, pin: pin, leaf: leaf}
}

func (p peerIdentity) request(method, path string, body []byte) *http.Request {
	var reader *bytes.Reader
	if body == nil {
		reader = bytes.NewReader(nil)
	} else {
		reader = bytes.NewReader(body)
	}
	req := httptest.NewRequest(method, path, reader)
	req.TLS = &tls.ConnectionState{PeerCertificates: []*x509.Certificate{p.leaf}}
	req.Header.Set("X-FileApex-Device-Id", p.id)
	return req
}

func newTestSigner(t *testing.T) (*ecdsa.PrivateKey, string) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return key, spkiBase64(&key.PublicKey)
}

func nodeInCluster(t *testing.T, peerIDs ...string) *Node {
	t.Helper()
	node, err := newNode(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	node.mu.Lock()
	node.joined = true
	for _, id := range peerIDs {
		node.peers[id] = deviceRecord{DeviceID: id, DeviceName: id, LastKnownIP: "192.168.1.2", Port: 8080}
	}
	node.mu.Unlock()
	return node
}

// writeGenesis makes the node the root of its own chain, standing in for the host that a joiner takes the chain from.
func writeGenesis(t *testing.T, node *Node) chainRecord {
	t.Helper()
	key, pub := node.signer()
	record, err := node.chain.append(key, node.deviceID(), kindGenesis, node.deviceID(), pub, node.tlsPinValue(), reasonPaired)
	if err != nil {
		t.Fatal(err)
	}
	return record
}

func TestTheSigningKeyIsMadeOnceKeptOwnerOnlyAndRenewedOnRequest(t *testing.T) {
	dir := t.TempDir()
	first, err := loadOrCreateSigningKey(dir)
	if err != nil {
		t.Fatal(err)
	}
	again, err := loadOrCreateSigningKey(dir)
	if err != nil {
		t.Fatal(err)
	}
	if spkiBase64(&first.PublicKey) != spkiBase64(&again.PublicKey) {
		t.Fatal("the key changed between two loads")
	}
	info, err := os.Stat(filepath.Join(dir, "chain_signing.key"))
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("signing key mode %v, want owner only", info.Mode().Perm())
	}
	renewed, err := renewSigningKey(dir)
	if err != nil {
		t.Fatal(err)
	}
	if spkiBase64(&renewed.PublicKey) == spkiBase64(&first.PublicKey) {
		t.Fatal("renewing gave the same key")
	}
	after, err := loadOrCreateSigningKey(dir)
	if err != nil {
		t.Fatal(err)
	}
	if spkiBase64(&after.PublicKey) != spkiBase64(&renewed.PublicKey) {
		t.Fatal("the renewed key was not kept")
	}
}

func TestTheChainSurvivesARestartAndAnUnreadableFileStartsEmpty(t *testing.T) {
	dir := t.TempDir()
	node, err := newNode(dir)
	if err != nil {
		t.Fatal(err)
	}
	writeGenesis(t, node)
	node.saveChain()
	head := node.chain.view().State.Head
	if head == "" {
		t.Fatal("no head after the genesis")
	}

	reborn, err := newNode(dir)
	if err != nil {
		t.Fatal(err)
	}
	if got := reborn.chain.view().State.Head; got != head {
		t.Fatalf("head after restart %q, want %q", got, head)
	}

	if err := os.WriteFile(filepath.Join(dir, "chain.json"), []byte("{broken"), 0o600); err != nil {
		t.Fatal(err)
	}
	empty, err := newNode(dir)
	if err != nil {
		t.Fatalf("an unreadable chain file stopped the node: %v", err)
	}
	if empty.chain.view().State.Seq != 0 {
		t.Fatal("an unreadable chain file produced records")
	}
}

func TestTheNodeAdvertisesTheChainProtocol(t *testing.T) {
	found := false
	for _, protocol := range supportedProtocols {
		if protocol == "fileapex.v2.membership.chain" {
			found = true
		}
	}
	if !found {
		t.Fatalf("supportedProtocols %v lack the chain protocol, so a host treats this node as key-less", supportedProtocols)
	}
}

func TestAPinnedPeerReadsTheChainWithProfilesAndAnUnknownDeviceIsTurnedAway(t *testing.T) {
	node := nodeInCluster(t, "phone")
	phone := pinnedPeer(t, node, "phone")
	genesis := writeGenesis(t, node)

	rec := httptest.NewRecorder()
	node.routes().ServeHTTP(rec, phone.request(http.MethodGet, "/api/v1/membership/chain", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("chain read %d: %s", rec.Code, rec.Body.String())
	}
	var answer struct {
		Head     string
		Records  []chainRecord
		Profiles []nodeState
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &answer); err != nil {
		t.Fatal(err)
	}
	if answer.Head != genesis.hash() || len(answer.Records) != 1 {
		t.Fatalf("answer %+v", answer)
	}
	if len(answer.Profiles) == 0 || answer.Profiles[0].DeviceID != node.deviceID() {
		t.Fatalf("profiles should start with the node's own: %+v", answer.Profiles)
	}

	stranger := peerIdentity{id: "stranger"}
	rec = httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/membership/chain", nil)
	node.routes().ServeHTTP(rec, req)
	if rec.Code == http.StatusOK {
		t.Fatalf("a request with no certificate read the chain: %v", stranger)
	}
}

func TestARemovedDeviceMayReadTheChainAndNothingElse(t *testing.T) {
	node := nodeInCluster(t)
	writeGenesis(t, node)
	gone := pinnedPeer(t, node, "gone")
	node.tls.revoke("gone")

	rec := httptest.NewRecorder()
	node.routes().ServeHTTP(rec, gone.request(http.MethodGet, "/api/v1/membership/chain", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("a removed device could not read its removal: %d", rec.Code)
	}
	var answer struct{ Profiles []nodeState }
	if err := json.Unmarshal(rec.Body.Bytes(), &answer); err != nil {
		t.Fatal(err)
	}
	if len(answer.Profiles) != 0 {
		t.Fatalf("a removed device learned the cluster: %+v", answer.Profiles)
	}

	for path, method := range map[string]string{"/api/v1/files/list": http.MethodGet, "/api/v1/identity/rename": http.MethodPost, "/api/v1/files/capabilities": http.MethodGet} {
		rec = httptest.NewRecorder()
		node.routes().ServeHTTP(rec, gone.request(method, path, nil))
		if rec.Code != http.StatusUnauthorized || rec.Header().Get("X-FileApex-Status") != "revoked" {
			t.Fatalf("%s for a removed device: %d, want 401 revoked", path, rec.Code)
		}
	}
	rec = httptest.NewRecorder()
	node.routes().ServeHTTP(rec, gone.request(http.MethodPost, "/api/v1/membership/chain", []byte(`{"records":[]}`)))
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("a removed device pushed a chain: %d", rec.Code)
	}
}

func TestAPushedChainIsCheckedKeptAndSaved(t *testing.T) {
	node := nodeInCluster(t, "host")
	host := pinnedPeer(t, node, "host")

	// The host's chain: its own genesis, then it admits this node.
	hostLog := newChainLog(func() string { return "host" })
	hostKey, hostPub := newTestSigner(t)
	genesis, err := hostLog.append(hostKey, "host", kindGenesis, "host", hostPub, host.pin, reasonPaired)
	if err != nil {
		t.Fatal(err)
	}
	_, pub := node.signer()
	admit, err := hostLog.append(hostKey, "host", kindAdmit, node.deviceID(), pub, node.tlsPinValue(), reasonPaired)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := json.Marshal(map[string]any{"records": []chainRecord{genesis, admit}})

	rec := httptest.NewRecorder()
	node.routes().ServeHTTP(rec, host.request(http.MethodPost, "/api/v1/membership/chain", body))
	if rec.Code != http.StatusOK {
		t.Fatalf("push %d: %s", rec.Code, rec.Body.String())
	}
	if got := node.chain.view().State.Head; got != admit.hash() {
		t.Fatalf("head %q, want %q", got, admit.hash())
	}
	saved, err := os.ReadFile(filepath.Join(node.dataDir, "chain.json"))
	if err != nil || !strings.Contains(string(saved), admit.Signature) {
		t.Fatalf("the pushed chain was not saved: %v", err)
	}

	forged := admit
	forged.Reason = "user_removed"
	other := nodeInCluster(t, "host")
	other.tls.record("host", host.pin, 9443)
	otherHost := host
	body, _ = json.Marshal(map[string]any{"records": []chainRecord{genesis, forged}})
	rec = httptest.NewRecorder()
	other.routes().ServeHTTP(rec, otherHost.request(http.MethodPost, "/api/v1/membership/chain", body))
	if other.chain.view().State.Seq > 1 {
		t.Fatal("a record with a bad signature was kept")
	}

	rec = httptest.NewRecorder()
	other.routes().ServeHTTP(rec, otherHost.request(http.MethodPost, "/api/v1/membership/chain", []byte("{nope")))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("garbage push %d, want 400", rec.Code)
	}
}

func TestAJoinIsAdmittedForTheCertificateThatAskedNotForTheBody(t *testing.T) {
	node := nodeInCluster(t, "phone")
	phone := pinnedPeer(t, node, "phone")
	writeGenesis(t, node)
	_, phonePub := newTestSigner(t)

	body, _ := json.Marshal(map[string]any{"key": phonePub, "tlsPin": strings.Repeat("a", 64)})
	rec := httptest.NewRecorder()
	node.routes().ServeHTTP(rec, phone.request(http.MethodPost, "/api/v1/membership/chain/join", body))
	if rec.Code != http.StatusOK {
		t.Fatalf("join %d: %s", rec.Code, rec.Body.String())
	}
	member, ok := node.chain.view().State.Members["phone"]
	if !ok || !member.Active || member.Key != phonePub {
		t.Fatalf("member after join: %+v present=%v", member, ok)
	}
	if member.TLSPin != phone.pin {
		t.Fatalf("the pin came from the body (%s), not the connection (%s)", member.TLSPin, phone.pin)
	}

	// A device that is not on this node's roster is never admitted.
	outsider := pinnedPeer(t, node, "outsider")
	node.mu.Lock()
	delete(node.peers, "outsider")
	node.mu.Unlock()
	_, outsiderPub := newTestSigner(t)
	body, _ = json.Marshal(map[string]any{"key": outsiderPub})
	rec = httptest.NewRecorder()
	node.routes().ServeHTTP(rec, outsider.request(http.MethodPost, "/api/v1/membership/chain/join", body))
	if _, ok := node.chain.view().State.Members["outsider"]; ok {
		t.Fatal("a device that is not on the roster was admitted")
	}
}

func TestEveryProfileWeSendHasAProtocolListBecauseKotlinRefusesNull(t *testing.T) {
	host, joiner := startedNode(t), startedNode(t)
	link(host, joiner)
	hostAdmits(t, host, joiner)
	// A roster entry that only has an id and a name, as a stub from the chain has until a profile fills it in.
	host.mu.Lock()
	host.peers[joiner.deviceID()] = deviceRecord{DeviceID: joiner.deviceID(), DeviceName: "stub", LastKnownIP: "127.0.0.1", Port: joiner.listenPort()}
	host.mu.Unlock()

	body, err := json.Marshal(host.answerChain(true, ""))
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(body, []byte(`"supportedProtocols":null`)) {
		t.Fatalf("a profile carries null protocols: %s", body)
	}
}

func TestThePairingMarksSurviveARestart(t *testing.T) {
	dir := t.TempDir()
	node, err := newNode(dir)
	if err != nil {
		t.Fatal(err)
	}
	writeGenesis(t, node)
	node.applier.pairedInPerson("host", 7)
	node.notePairedInPerson()

	reborn, err := newNode(dir)
	if err != nil {
		t.Fatal(err)
	}
	if !reborn.pairedRecently() {
		t.Fatal("a restart lost the in-person pairing, so a removed node could not take a new signing key")
	}
	if !reborn.applier.heldBack("host", 7) || reborn.applier.heldBack("host", 8) {
		t.Fatal("a restart lost the hold on a removal that was already in the chain, or widened it")
	}

	node.mu.Lock()
	node.pairedAt = time.Time{}
	node.mu.Unlock()
	node.saveChain()
	cleared, err := newNode(dir)
	if err != nil {
		t.Fatal(err)
	}
	if cleared.pairedRecently() {
		t.Fatal("a used pairing mark came back after a restart")
	}
}
