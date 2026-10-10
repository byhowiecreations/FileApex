package main

import (
	"bytes"
	"crypto/ecdh"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestTLSIdentityIsStableAndReissuesOnTheSameKey(t *testing.T) {
	dir := t.TempDir()
	now := time.Now()
	first, err := loadOrCreateTLSIdentity(dir, now)
	if err != nil {
		t.Fatal(err)
	}
	again, err := loadOrCreateTLSIdentity(dir, now)
	if err != nil || again.pin != first.pin {
		t.Fatalf("pin changed across reload: %v %s %s", err, first.pin, again.pin)
	}
	if len(first.pin) != 64 {
		t.Fatalf("pin length %d", len(first.pin))
	}
	late, err := loadOrCreateTLSIdentity(dir, now.Add(tlsCertRenewAfter+48*time.Hour))
	if err != nil || late.pin != first.pin {
		t.Fatalf("pin changed on reissue: %v", err)
	}
	if string(late.cert.Certificate[0]) == string(first.cert.Certificate[0]) {
		t.Fatal("certificate was not reissued")
	}
	info, err := os.Stat(filepath.Join(dir, "tls_key.pem"))
	if err != nil || info.Mode().Perm() != 0o600 {
		t.Fatalf("key file mode: %v %v", err, info)
	}
}

func TestUnreadableKeyIsNeverReplacedSilently(t *testing.T) {
	dir := t.TempDir()
	if _, err := loadOrCreateTLSIdentity(dir, time.Now()); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "tls_key.pem"), []byte("garbage"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := loadOrCreateTLSIdentity(dir, time.Now()); err == nil {
		t.Fatal("expected an error for a corrupt key")
	}
}

// Vector produced by the Kotlin ClipboardCrypto: a encrypts to b with salt "device-a|device-b".
func TestPairCryptoOpensWhatKotlinSealed(t *testing.T) {
	bPriv, _ := base64.StdEncoding.DecodeString("ZWZnaGlqa2xtbm9wcXJzdHV2d3h5ent8fX5/gIGCg4Q=")
	b, err := ecdh.X25519().NewPrivateKey(bPriv)
	if err != nil {
		t.Fatal(err)
	}
	const aPub = "B6N8vBQgk8i3VdwbEOhstCY3StFqqFPtC9/AsrhtHHw="
	const ct = "zopp+rKWUgl2uJV9cxm37J8L4xri2t0vOqbIKPw2YsoHTPsvw+i5I4bXEre6"
	plain, err := openPair(b, aPub, pairSalt("device-b", "device-a"), ct)
	if err != nil || string(plain) != "hello from kotlin" {
		t.Fatalf("got %q err %v", plain, err)
	}
	if _, err := openPair(b, aPub, pairSalt("device-b", "someone-else"), ct); err == nil {
		t.Fatal("wrong salt must not open")
	}
}

func TestPairCryptoRoundTripsBetweenGoKeys(t *testing.T) {
	a, _ := ecdh.X25519().GenerateKey(bytes.NewReader(bytes.Repeat([]byte{7}, 64)))
	b, _ := ecdh.X25519().GenerateKey(bytes.NewReader(bytes.Repeat([]byte{9}, 64)))
	aPub := base64.StdEncoding.EncodeToString(a.PublicKey().Bytes())
	bPub := base64.StdEncoding.EncodeToString(b.PublicKey().Bytes())
	sealed, err := sealPair(a, bPub, "x|y", []byte("secret"))
	if err != nil {
		t.Fatal(err)
	}
	plain, err := openPair(b, aPub, "x|y", sealed)
	if err != nil || string(plain) != "secret" {
		t.Fatalf("got %q err %v", plain, err)
	}
}

func TestRequiresTLSMatchesTheAppsPolicy(t *testing.T) {
	for _, p := range []string{"/api/v1/clipboard/send", "/api/v1/files/list?path=/", "/api/v1/devices/merge", "/api/v1/identity/rename", "/api/v1/cluster/remove"} {
		if !requiresTLS(p) {
			t.Errorf("%s should need TLS", p)
		}
	}
	for _, p := range []string{"/api/v1/pairing/respond", "/api/v1/auth/verify-pin", "/api/v1/tls/pin", "/api/v1/identity", "/api/v1/identity?from=x", "/api/v1/heartbeat", "/api/v1/health"} {
		if requiresTLS(p) {
			t.Errorf("%s should stay open", p)
		}
	}
}

func newTestNode(t *testing.T, id, name string) *Node {
	t.Helper()
	n, err := newNode(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	n.port = 0
	n.identity.DeviceID = id
	n.identity.DeviceName = name
	n.allowLoopback = true
	return n
}

func startNode(t *testing.T, n *Node) (httpPort, tlsPort int) {
	t.Helper()
	if err := n.serve(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { n.shutdown(time.Second) })
	deadline := time.Now().Add(3 * time.Second)
	for n.tlsPort() == 0 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	return n.listenPort(), n.tlsPort()
}

// pairUp makes each node know the other as a paired peer with its pairing key.
func pairUp(a, b *Node, aHTTP, bHTTP int) {
	a.peers[b.identity.DeviceID] = deviceRecord{DeviceID: b.identity.DeviceID, DeviceName: "B", LastKnownIP: "127.0.0.1", Port: bHTTP, PublicKey: b.pairPublicKey(), ClusterVersion: time.Now().UnixMilli()}
	b.peers[a.identity.DeviceID] = deviceRecord{DeviceID: a.identity.DeviceID, DeviceName: "A", LastKnownIP: "127.0.0.1", Port: aHTTP, PublicKey: a.pairPublicKey(), ClusterVersion: time.Now().UnixMilli()}
}

func plainGet(t *testing.T, url string) (int, string) {
	t.Helper()
	resp, err := http.Get(url)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, strings.TrimSpace(string(body))
}

func TestAnnouncementPinsBothSidesAndSwitchesToTLS(t *testing.T) {
	a := newTestNode(t, "dev-a", "A")
	b := newTestNode(t, "dev-b", "B")
	aHTTP, _ := startNode(t, a)
	bHTTP, _ := startNode(t, b)
	pairUp(a, b, aHTTP, bHTTP)

	if err := a.announceTLSTo(t.Context(), a.peers["dev-b"], time.Now()); err != nil {
		t.Fatal(err)
	}
	if p, ok := b.tls.peer("dev-a"); !ok || p.Pin != a.tlsPinValue() {
		t.Fatalf("B did not pin A: %+v %v", p, ok)
	}
	if p, ok := a.tls.peer("dev-b"); !ok || p.Pin != b.tlsPinValue() {
		t.Fatalf("A did not pin B from the reply: %+v %v", p, ok)
	}

	// Plain HTTP naming a pinned peer is refused on protected routes, open ones still work.
	status, body := plainGet(t, "http://127.0.0.1:"+itoa(bHTTP)+"/api/v1/files/capabilities?from=dev-a")
	if status != 403 || body != "tls_required" {
		t.Fatalf("plain from pinned peer: %d %q", status, body)
	}
	if status, _ := plainGet(t, "http://127.0.0.1:"+itoa(bHTTP)+"/api/v1/identity?from=dev-a"); status != 200 {
		t.Fatalf("identity must stay open, got %d", status)
	}
	// A stranger is not affected.
	if status, body := plainGet(t, "http://127.0.0.1:"+itoa(bHTTP)+"/api/v1/files/capabilities?from=stranger"); body == "tls_required" {
		t.Fatalf("stranger was told tls_required (%d)", status)
	}

	// The node's own client reaches B over TLS and is identified by its certificate.
	req, _ := http.NewRequest(http.MethodGet, "http://127.0.0.1:"+itoa(bHTTP)+"/api/v1/files/capabilities?from=dev-a", nil)
	respBody, status, err := a.do(req, 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if status == 403 && strings.Contains(string(respBody), "tls_required") {
		t.Fatalf("client fell back to plain HTTP: %s", respBody)
	}
}

func TestTLSServerRejectsStrangersAndSpoofedSenders(t *testing.T) {
	a := newTestNode(t, "dev-a", "A")
	b := newTestNode(t, "dev-b", "B")
	aHTTP, _ := startNode(t, a)
	bHTTP, bTLS := startNode(t, b)
	pairUp(a, b, aHTTP, bHTTP)
	if err := a.announceTLSTo(t.Context(), a.peers["dev-b"], time.Now()); err != nil {
		t.Fatal(err)
	}
	dial := func(identity *tlsIdentity, pins map[string]bool) (*http.Client, error) {
		cfg := (&tlsState{identity: identity}).clientConfig(pins)
		return &http.Client{Transport: &http.Transport{TLSClientConfig: cfg}, Timeout: 5 * time.Second}, nil
	}
	url := "https://127.0.0.1:" + itoa(bTLS) + "/api/v1/identity"

	stranger, _ := loadOrCreateTLSIdentity(t.TempDir(), time.Now())
	client, _ := dial(stranger, map[string]bool{b.tlsPinValue(): true})
	if resp, err := client.Get(url); err == nil {
		resp.Body.Close()
		t.Fatalf("stranger certificate was served (%d)", resp.StatusCode)
	}

	paired, _ := dial(a.tls.identity, map[string]bool{b.tlsPinValue(): true})
	resp, err := paired.Get(url + "?from=dev-b")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != 403 {
		t.Fatalf("spoofed from over TLS: %d", resp.StatusCode)
	}
	resp, err = paired.Get(url + "?from=dev-a")
	if err != nil || resp.StatusCode != 200 {
		t.Fatalf("paired request failed: %v %v", err, resp)
	}
	resp.Body.Close()

	wrong, _ := dial(a.tls.identity, map[string]bool{strings.Repeat("0", 64): true})
	if resp, err := wrong.Get(url); err == nil {
		resp.Body.Close()
		t.Fatal("client accepted a server with the wrong key")
	}
}

func TestForgedAnnouncementsAreRefused(t *testing.T) {
	a := newTestNode(t, "dev-a", "A")
	b := newTestNode(t, "dev-b", "B")
	aHTTP, _ := startNode(t, a)
	bHTTP, _ := startNode(t, b)
	pairUp(a, b, aHTTP, bHTTP)
	good, _ := a.buildAnnouncement(a.peers["dev-b"], time.Now())

	attacker, _ := ecdh.X25519().GenerateKey(bytes.NewReader(bytes.Repeat([]byte{3}, 64)))
	forgedKey := good
	forgedKey.SenderPublicKey = base64.StdEncoding.EncodeToString(attacker.PublicKey().Bytes())
	for name, ann := range map[string]tlsAnnouncement{
		"unknown sender": {SenderDeviceID: "nobody", SenderPublicKey: good.SenderPublicKey, Ciphertext: good.Ciphertext},
		"forged key":     forgedKey,
		"garbage":        {SenderDeviceID: "dev-a", SenderPublicKey: good.SenderPublicKey, Ciphertext: "AAAA"},
	} {
		payload, _ := json.Marshal(ann)
		resp, err := http.Post("http://127.0.0.1:"+itoa(bHTTP)+"/api/v1/tls/pin", "application/json", bytes.NewReader(payload))
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode < 400 {
			t.Errorf("%s accepted with %d", name, resp.StatusCode)
		}
	}
	if _, ok := b.tls.peer("dev-a"); ok {
		t.Fatal("a forged announcement pinned a key")
	}
	// A stale announcement is refused too.
	old, _ := a.buildAnnouncement(a.peers["dev-b"], time.Now().Add(-3*24*time.Hour))
	if got := b.acceptAnnouncement(old, time.Now()); got != announceInvalid {
		t.Fatalf("stale announcement: %v", got)
	}
}

func TestDifferentPinForAPinnedPeerIsNotAccepted(t *testing.T) {
	a := newTestNode(t, "dev-a", "A")
	b := newTestNode(t, "dev-b", "B")
	aHTTP, _ := startNode(t, a)
	bHTTP, _ := startNode(t, b)
	pairUp(a, b, aHTTP, bHTTP)
	if err := a.announceTLSTo(t.Context(), a.peers["dev-b"], time.Now()); err != nil {
		t.Fatal(err)
	}
	original, _ := b.tls.peer("dev-a")
	b.tls.record("dev-a", strings.Repeat("a", 64), original.Port)
	ann, _ := a.buildAnnouncement(a.peers["dev-b"], time.Now())
	if got := b.acceptAnnouncement(ann, time.Now()); got != announceKeyChanged {
		t.Fatalf("got %v", got)
	}
	if p, _ := b.tls.peer("dev-a"); p.Pin != strings.Repeat("a", 64) {
		t.Fatal("the pinned key was replaced")
	}
}

func TestRemovingAPeerForgetsItsPinAndClosesTheDoor(t *testing.T) {
	a := newTestNode(t, "dev-a", "A")
	b := newTestNode(t, "dev-b", "B")
	aHTTP, _ := startNode(t, a)
	bHTTP, bTLS := startNode(t, b)
	pairUp(a, b, aHTTP, bHTTP)
	if err := a.announceTLSTo(t.Context(), a.peers["dev-b"], time.Now()); err != nil {
		t.Fatal(err)
	}
	b.removePeer("dev-a")
	if _, ok := b.tls.peer("dev-a"); ok {
		t.Fatal("pin survived removal")
	}
	cfg := a.tls.clientConfig(map[string]bool{b.tlsPinValue(): true})
	conn, err := tls.DialWithDialer(&net.Dialer{Timeout: 3 * time.Second}, "tcp", "127.0.0.1:"+itoa(bTLS), cfg)
	if err == nil {
		defer conn.Close()
		conn.SetReadDeadline(time.Now().Add(2 * time.Second))
		if _, err := conn.Write([]byte("GET /api/v1/identity HTTP/1.1\r\nHost: x\r\n\r\n")); err == nil {
			buf := make([]byte, 16)
			if n, _ := conn.Read(buf); n > 0 {
				t.Fatalf("removed peer was served: %q", buf[:n])
			}
		}
	}
}

func TestSelfStateAdvertisesPinPortAndPairingKey(t *testing.T) {
	n := newTestNode(t, "dev-n", "N")
	startNode(t, n)
	state := n.selfState()
	if state.TLSPin != n.tls.identity.pin || state.TLSPort != n.tlsPort() || state.TLSPort == 0 {
		t.Fatalf("state %+v", state)
	}
	raw, err := base64.StdEncoding.DecodeString(state.PublicKey)
	if err != nil || len(raw) != 32 {
		t.Fatalf("public key %q", state.PublicKey)
	}
}

func itoa(v int) string { return strconv.Itoa(v) }
