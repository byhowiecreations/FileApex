package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

// pairingFront stands in for a host that is showing its pairing screen: the real daemon `host` answers everything
// except the pairing steps, which a node never hosts. It pins the joiner when the user accepts, as a real host does.
type pairingFront struct {
	broadcaster *httptest.Server
	tlsServer   *httptest.Server
	beacon      pairingBeacon
	seenPaths   []string
	nonceA      string
	commit      string
	joiner      deviceRecord
}

func startPairingFront(t *testing.T, host *Node, prefix string) *pairingFront {
	t.Helper()
	front := &pairingFront{nonceA: "0a1b2c3d4e5f60718293a4b5c6d7e8f9"}
	guarded := host.guard(host.routes(), true)
	front.tlsServer = httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasPrefix(r.URL.Path, "/api/") || !strings.Contains(r.URL.Path, "/pairing/") {
			guarded.ServeHTTP(w, r)
			return
		}
		front.seenPaths = append(front.seenPaths, r.URL.Path)
		if !strings.HasPrefix(r.URL.Path, prefix+"/") {
			http.Error(w, "pairing_only", http.StatusForbidden)
			return
		}
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		joinerPin, _ := leafPin(r.TLS.PeerCertificates[0])
		switch strings.TrimPrefix(r.URL.Path, prefix+"/") {
		case "offer":
			front.commit, _ = body["commit"].(string)
			raw, _ := json.Marshal(body["scannerDevice"])
			_ = json.Unmarshal(raw, &front.joiner)
			_ = json.NewEncoder(w).Encode(map[string]string{"sessionId": "s1", "nonceA": front.nonceA})
		case "reveal":
			nonceB, _ := body["nonceB"].(string)
			if front.commit != pairCommit(nonceB, joinerPin, front.joiner.DeviceID) {
				http.Error(w, "pairing_code_invalid", http.StatusForbidden)
			}
		case "decide":
			// The user accepted: the host pins the joiner and puts it on its roster.
			host.tls.record(front.joiner.DeviceID, joinerPin, front.joiner.Port+1)
			front.joiner.LastKnownIP = "127.0.0.1"
			host.mu.Lock()
			host.joined = true
			host.peers[front.joiner.DeviceID] = front.joiner
			host.mu.Unlock()
			w.WriteHeader(http.StatusCreated)
		default:
			http.NotFound(w, r)
		}
	}))
	front.tlsServer.TLS = &tls.Config{
		Certificates:          []tls.Certificate{host.tls.current().cert},
		ClientAuth:            tls.RequireAnyClientCert,
		MinVersion:            tls.VersionTLS12,
		VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error { return nil },
	}
	front.tlsServer.StartTLS()
	t.Cleanup(front.tlsServer.Close)
	tlsURL, _ := url.Parse(front.tlsServer.URL)
	tlsPort, _ := strconv.Atoi(tlsURL.Port())

	front.broadcaster = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/identity" {
			http.NotFound(w, r)
			return
		}
		state := host.selfState()
		state.IPAddress = "127.0.0.1"
		state.TLSPort = tlsPort
		_ = json.NewEncoder(w).Encode(state)
	}))
	t.Cleanup(front.broadcaster.Close)
	bURL, _ := url.Parse(front.broadcaster.URL)
	bPort, _ := strconv.Atoi(bURL.Port())
	front.beacon = pairingBeacon{DeviceName: host.deviceName(), IPAddress: "127.0.0.1", Port: bPort, DeviceID: host.deviceID()}
	host.port = bPort
	return front
}

func joinerNode(t *testing.T) *Node {
	t.Helper()
	node := startedNode(t)
	node.forceAdvertise = "127.0.0.1"
	return node
}

func TestAJoinerPairsOnProtocol2TakesTheHostsClusterAndIsAdmitted(t *testing.T) {
	host := startedNode(t)
	writeGenesis(t, host)
	front := startPairingFront(t, host, "/api/v2/pairing/secure")
	joiner := joinerNode(t)

	if err := joiner.handshake(context.Background(), front.beacon, ""); err != nil {
		t.Fatal(err)
	}
	for _, p := range front.seenPaths {
		if !strings.HasPrefix(p, "/api/v2/pairing/secure/") {
			t.Fatalf("pairing used %s", p)
		}
	}
	if !joiner.isJoined() {
		t.Fatal("not joined")
	}
	member := joiner.chain.view().State.Members[joiner.deviceID()]
	if !member.Active || member.Key != keyOf(joiner) {
		t.Fatalf("the joiner is not an active member under its key: %+v", member)
	}
	if !host.chain.view().State.Members[joiner.deviceID()].Active {
		t.Fatal("the host does not hold the joiner as active")
	}
	if joiner.chain.rootHash() != host.chain.rootHash() {
		t.Fatal("the joiner is not on the host's cluster")
	}
	if front.joiner.DeviceID != joiner.deviceID() || front.joiner.Platform != "linux" || front.joiner.RootPath != joiner.inboxDir() {
		t.Fatalf("the offer carried %+v", front.joiner)
	}
}

func TestAHostThatDoesNotSpeakProtocol2StopsThePairingWithAnUpdateMessage(t *testing.T) {
	host := startedNode(t)
	writeGenesis(t, host)
	front := startPairingFront(t, host, "/api/v1/pairing/secure")
	joiner := joinerNode(t)

	err := joiner.handshake(context.Background(), front.beacon, "")
	if err == nil || !strings.Contains(strings.ToLower(err.Error()), "update") {
		t.Fatalf("error %v, want a message that the other device needs an update", err)
	}
	if joiner.isJoined() {
		t.Fatal("joined a host that is not on protocol 2")
	}
}

func TestARemovedNodeCanPairAgainAtOnceUnderNewKeys(t *testing.T) {
	host := startedNode(t)
	writeGenesis(t, host)
	front := startPairingFront(t, host, "/api/v2/pairing/secure")
	joiner := joinerNode(t)
	if err := joiner.handshake(context.Background(), front.beacon, ""); err != nil {
		t.Fatal(err)
	}
	firstKey := keyOf(joiner)

	hostKey, _ := host.signer()
	member := host.chain.view().State.Members[joiner.deviceID()]
	if _, err := host.chain.append(hostKey, host.deviceID(), kindRemove, joiner.deviceID(), member.Key, member.TLSPin, reasonUserRemoved); err != nil {
		t.Fatal(err)
	}
	host.applyChainNow()
	joiner.tickChain(context.Background())
	if joiner.isJoined() {
		t.Fatal("the removal did not reach the node")
	}

	if err := joiner.handshake(context.Background(), front.beacon, ""); err != nil {
		t.Fatalf("a removed node could not pair again: %v", err)
	}
	again := joiner.chain.view().State.Members[joiner.deviceID()]
	if !again.Active || again.Key == firstKey || again.Key != keyOf(joiner) {
		t.Fatalf("after pairing again: %+v (first key %s)", again, firstKey[:8])
	}
}

func TestTheOldMembershipProtocolIsGone(t *testing.T) {
	node := nodeInCluster(t)
	for _, path := range []string{"/api/v1/devices/merge", "/api/v1/devices/removed", "/api/v1/cluster/remove", "/cluster/remove", "/api/v1/devices"} {
		rec := httptest.NewRecorder()
		node.routes().ServeHTTP(rec, httptest.NewRequest(http.MethodPost, path+"?from=x", strings.NewReader("{}")))
		if rec.Code != http.StatusNotFound {
			t.Fatalf("%s answered %d, want 404", path, rec.Code)
		}
	}
	health := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/health", nil)
	req.TLS = &tls.ConnectionState{}
	node.routes().ServeHTTP(health, req)
	for name := range health.Header() {
		if strings.Contains(strings.ToLower(name), "removals") || strings.Contains(strings.ToLower(name), "digest") {
			t.Fatalf("health still carries %s", name)
		}
	}
	body, _ := json.Marshal(node.selfState())
	for _, field := range []string{"membershipVersion", "membershipProtocol", "clusterVersion"} {
		if strings.Contains(string(body), field) {
			t.Fatalf("identity still carries %s", field)
		}
	}
}

func TestResettingWritesALeaveSoTheRestOfTheClusterLetsTheNodeGo(t *testing.T) {
	host, joiner := startedNode(t), startedNode(t)
	link(host, joiner)
	hostAdmits(t, host, joiner)
	joiner.tickChain(context.Background())
	joiner.saveTailscale("auth_key", "tskey-auth-test")

	joiner.resetCluster(context.Background())

	if joiner.isJoined() || len(joiner.roster()) != 0 {
		t.Fatal("the node kept its cluster after a reset")
	}
	if mode, key := joiner.tailscaleSettings(); mode != "" || key != "" {
		t.Fatalf("tailscale settings survived a reset: %q %q", mode, key)
	}
	if host.chain.view().State.Members[joiner.deviceID()].Active {
		t.Fatal("the host never saw the leave")
	}
	if joiner.deviceID() == "" {
		t.Fatal("the device id must be kept")
	}
}

func TestAPairedPeerUploadsAStrangerAndAnEscapeDoNot(t *testing.T) {
	node := nodeInCluster(t, "host-1", "peer-2")
	trusted := httptest.NewServer(node.routes())
	defer trusted.Close()
	tport, _ := strconv.Atoi(strings.TrimPrefix(trusted.URL, "http://127.0.0.1:"))
	target := filepath.Join(node.inboxDir(), "backup.txt")
	if got := postUpload(t, tport, target, "stranger", "nope", 0, 0); got != http.StatusForbidden {
		t.Fatalf("stranger status = %d", got)
	}
	if got := postUpload(t, tport, filepath.Join(node.inboxDir(), "..", "cluster.json"), "host-1", "x", 0, 0); got != http.StatusForbidden {
		t.Fatalf("escape status = %d", got)
	}
	if got := postUpload(t, tport, target, "host-1", "hello", 0, 0); got != http.StatusCreated {
		t.Fatalf("upload status = %d", got)
	}
	if body, err := os.ReadFile(target); err != nil || string(body) != "hello" {
		t.Fatalf("file = %q %v", body, err)
	}
	partTarget := filepath.Join(node.inboxDir(), "partial.bin")
	if got := postUpload(t, tport, partTarget, "peer-2", "ab", 0, 4); got != http.StatusBadRequest {
		t.Fatalf("partial status = %d", got)
	}
	resume := getJSON(t, "http://127.0.0.1:"+strconv.Itoa(tport)+"/api/v1/files/resume?from=peer-2&expectedSize=4&targetPath="+url.QueryEscape(partTarget))
	if resume["offset"] != float64(2) || resume["complete"] != false {
		t.Fatalf("resume = %+v", resume)
	}
	if got := postUpload(t, tport, partTarget, "peer-2", "cd", 2, 4); got != http.StatusCreated {
		t.Fatalf("resume upload status = %d", got)
	}
	if body, err := os.ReadFile(partTarget); err != nil || string(body) != "abcd" {
		t.Fatalf("partial file = %q %v", body, err)
	}
	if _, err := os.Stat(partTarget + partSuffix); !os.IsNotExist(err) {
		t.Fatalf("part file still present: %v", err)
	}
}

func TestTheIdentityOverPlainHTTPShowsAStrangerNoHardwareAndNoShareFolder(t *testing.T) {
	node := nodeInCluster(t)
	server := httptest.NewServer(node.routes())
	defer server.Close()
	resp, err := http.Get(server.URL + "/api/v1/identity")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var state nodeState
	if err := json.NewDecoder(resp.Body).Decode(&state); err != nil {
		t.Fatal(err)
	}
	if state.Platform != "" || state.RootPath != "/" {
		t.Fatalf("a stranger learned %+v", state)
	}
	if own := node.selfState(); own.Platform != "linux" {
		t.Fatalf("own identity = %+v", own)
	}
	_ = time.Now
}
