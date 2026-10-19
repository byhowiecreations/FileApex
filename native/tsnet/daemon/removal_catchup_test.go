package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"testing"
	"time"
)

func nodeWithPeers(t *testing.T, ids ...string) *Node {
	t.Helper()
	node, err := newNode(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	node.mu.Lock()
	for _, id := range ids {
		node.peers[id] = deviceRecord{DeviceID: id, DeviceName: id, LastKnownIP: "192.168.1.2", Port: 8080, ClusterVersion: 100}
	}
	node.mu.Unlock()
	return node
}

func healthRemovals(node *Node, withTLS bool) string {
	req := httptest.NewRequest(http.MethodGet, "/api/v1/health", nil)
	if withTLS {
		req.TLS = &tls.ConnectionState{}
	}
	rec := httptest.NewRecorder()
	node.handleHealth(rec, req)
	return rec.Header().Get(removalsHeader)
}

func addTombstone(node *Node, id string, version int64) {
	node.mu.Lock()
	node.tombstones[id] = diskTomb{DeviceID: id, Version: version}
	node.mu.Unlock()
}

func TestHealthShowsTheRemovalFingerprintOnlyToAPeerThatPresentedItsCertificate(t *testing.T) {
	node := nodeWithPeers(t, "aaaaaaaa-1111")
	if got := healthRemovals(node, false); got != "" {
		t.Fatalf("a stranger saw %q", got)
	}
	if got := healthRemovals(node, true); got != "0" {
		t.Fatalf("no removals should read 0, got %q", got)
	}
	addTombstone(node, "gone-1", time.Now().UnixMilli())
	got := healthRemovals(node, true)
	if got == "0" || len(got) != 16 {
		t.Fatalf("fingerprint %q", got)
	}
}

func TestTheFingerprintIgnoresOrderAndOldRecords(t *testing.T) {
	now := time.Now().UnixMilli()
	a := nodeWithPeers(t)
	b := nodeWithPeers(t)
	addTombstone(a, "x-1", now)
	addTombstone(a, "y-2", now-1000)
	addTombstone(b, "y-2", now-1000)
	addTombstone(b, "x-1", now)
	if a.removalsDigest() != b.removalsDigest() {
		t.Fatal("same records in another order must give the same fingerprint")
	}
	addTombstone(a, "ancient-3", now-90*24*60*60*1000)
	if a.removalsDigest() != b.removalsDigest() {
		t.Fatal("a record older than the window must not change the fingerprint")
	}
}

func TestADifferentStampChangesTheFingerprint(t *testing.T) {
	now := time.Now().UnixMilli()
	a := nodeWithPeers(t)
	b := nodeWithPeers(t)
	addTombstone(a, "x-1", now)
	addTombstone(b, "x-1", now+5)
	if a.removalsDigest() == b.removalsDigest() {
		t.Fatal("a re-stamped removal must read as different")
	}
}

func TestRemovedEndpointListsTheWindowedRecordsForAPairedPeer(t *testing.T) {
	node := nodeWithHost(t)
	addTombstone(node, "gone-1", time.Now().UnixMilli())
	addTombstone(node, "ancient-2", time.Now().Add(-90*24*time.Hour).UnixMilli())
	req := httptest.NewRequest(http.MethodGet, "/api/v1/devices/removed?from=host-1", nil)
	rec := httptest.NewRecorder()
	node.handleRemoved(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("status %d", rec.Code)
	}
	var out []removedRecord
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	if len(out) != 1 || out[0].DeviceID != "gone-1" || out[0].MembershipProtocol != membershipProtocol {
		t.Fatalf("records %+v", out)
	}
}

// A removal pulled from a peer drops a device we still hold, once, and a re-paired device with a newer stamp survives it.
func TestAPulledRemovalDropsAGhostButNotADeviceRepairedAfterwards(t *testing.T) {
	peerNode := nodeWithPeers(t)
	now := time.Now().UnixMilli()
	removedAt := now - 1000
	addTombstone(peerNode, "oppo-0001", removedAt)
	ghost := nodeWithPeers(t, "peer-1")
	peerNode.mu.Lock()
	peerNode.peers[ghost.identity.DeviceID] = deviceRecord{DeviceID: ghost.identity.DeviceID, DeviceName: "puller", LastKnownIP: "192.168.1.3", Port: 8080, ClusterVersion: 100}
	peerNode.mu.Unlock()
	server := httptest.NewServer(peerNode.routes())
	defer server.Close()
	host, port := splitHostPort(t, server.URL)

	ghost.allowLoopback = true
	ghost.mu.Lock()
	ghost.peers["oppo-0001"] = deviceRecord{DeviceID: "oppo-0001", DeviceName: "OPPO", LastKnownIP: "192.168.1.9", Port: 8080, ClusterVersion: removedAt - 5000}
	ghost.peers["repaired-0002"] = deviceRecord{DeviceID: "repaired-0002", DeviceName: "Back", LastKnownIP: "192.168.1.10", Port: 8080, ClusterVersion: now}
	ghost.mu.Unlock()
	addTombstone(peerNode, "repaired-0002", removedAt)

	peer := deviceRecord{DeviceID: "peer-1", LastKnownIP: host, Port: port}
	ghost.mu.Lock()
	ghost.peers["peer-1"] = peer
	ghost.mu.Unlock()
	if err := ghost.pullRemovals(context.Background(), peer); err != nil {
		t.Fatal(err)
	}
	ghost.mu.Lock()
	_, oppo := ghost.peers["oppo-0001"]
	_, back := ghost.peers["repaired-0002"]
	ghost.mu.Unlock()
	if oppo {
		t.Fatal("the ghost should be gone")
	}
	if !back {
		t.Fatal("a device re-paired after the removal must survive an older removal")
	}
}

func TestAPulledRecordNamingThisNodeIsIgnored(t *testing.T) {
	node := nodeWithPeers(t, "peer-1")
	node.mu.Lock()
	node.membership = 100
	node.mu.Unlock()
	reply := []removedRecord{{DeviceID: node.identity.DeviceID, ClusterVersion: time.Now().UnixMilli(), MembershipProtocol: membershipProtocol}}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, reply)
	}))
	defer server.Close()
	host, port := splitHostPort(t, server.URL)
	peer := deviceRecord{DeviceID: "peer-1", LastKnownIP: host, Port: port}
	if err := node.pullRemovals(context.Background(), peer); err != nil {
		t.Fatal(err)
	}
	if node.wasRemoved() {
		t.Fatal("a pulled record must never remove this node")
	}
}

func TestAFingerprintIsPulledOncePerDistinctValue(t *testing.T) {
	calls := 0
	peerNode := nodeWithPeers(t)
	addTombstone(peerNode, "gone-1", time.Now().UnixMilli())
	node := nodeWithPeers(t)
	peerNode.mu.Lock()
	peerNode.peers[node.identity.DeviceID] = deviceRecord{DeviceID: node.identity.DeviceID, DeviceName: "puller", LastKnownIP: "192.168.1.3", Port: 8080, ClusterVersion: 100}
	peerNode.mu.Unlock()
	inner := peerNode.routes()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		inner.ServeHTTP(w, r)
	}))
	defer server.Close()
	host, port := splitHostPort(t, server.URL)
	peer := deviceRecord{DeviceID: "peer-1", LastKnownIP: host, Port: port}
	node.mu.Lock()
	node.peers["peer-1"] = peer
	node.mu.Unlock()
	theirs := peerNode.removalsDigest()
	node.noteRemovalsDigest(context.Background(), peer, theirs)
	node.noteRemovalsDigest(context.Background(), peer, theirs)
	node.noteRemovalsDigest(context.Background(), peer, theirs)
	if calls != 1 {
		t.Fatalf("pulled %d times for one fingerprint", calls)
	}
	node.noteRemovalsDigest(context.Background(), peer, "ffffffffffffffff")
	if calls != 2 {
		t.Fatalf("a new fingerprint should pull again, calls %d", calls)
	}
	same := node.removalsDigest()
	node.noteRemovalsDigest(context.Background(), peer, same)
	if calls != 2 {
		t.Fatal("an equal fingerprint must not pull")
	}
}

func splitHostPort(t *testing.T, raw string) (string, int) {
	t.Helper()
	u, err := url.Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	port, _ := strconv.Atoi(u.Port())
	return u.Hostname(), port
}

func TestAPeerSilentForThirtyDaysIsNotAnnouncedTo(t *testing.T) {
	node := nodeWithPeers(t, "quiet-1", "heard-1", "new-1")
	node.mu.Lock()
	node.lastHeard["quiet-1"] = time.Now().Add(-31 * 24 * time.Hour).UnixMilli()
	node.lastHeard["heard-1"] = time.Now().Add(-2 * 24 * time.Hour).UnixMilli()
	node.mu.Unlock()
	if !node.silentTooLong("quiet-1") {
		t.Fatal("a peer silent for 31 days should be skipped")
	}
	if node.silentTooLong("heard-1") {
		t.Fatal("a peer heard 2 days ago should be announced to")
	}
	if node.silentTooLong("new-1") {
		t.Fatal("a peer with no record starts its clock and is announced to")
	}
}

func TestAnAnswerFromASilentPeerStartsItsClockAgain(t *testing.T) {
	node := nodeWithPeers(t, "quiet-1")
	node.mu.Lock()
	node.lastHeard["quiet-1"] = time.Now().Add(-40 * 24 * time.Hour).UnixMilli()
	node.mu.Unlock()
	node.markHeard("quiet-1")
	if node.silentTooLong("quiet-1") {
		t.Fatal("a peer that just answered must not be skipped")
	}
}

func TestLastHeardSurvivesARestart(t *testing.T) {
	dir := t.TempDir()
	node, err := newNode(dir)
	if err != nil {
		t.Fatal(err)
	}
	old := time.Now().Add(-45 * 24 * time.Hour).UnixMilli()
	node.mu.Lock()
	node.peers["quiet-1"] = deviceRecord{DeviceID: "quiet-1", DeviceName: "Q", LastKnownIP: "192.168.1.2", Port: 8080, ClusterVersion: 100}
	node.lastHeard["quiet-1"] = old
	node.persistLocked()
	node.mu.Unlock()
	again, err := newNode(dir)
	if err != nil {
		t.Fatal(err)
	}
	if !again.silentTooLong("quiet-1") {
		t.Fatal("the silence was forgotten across a restart")
	}
}

// The same two records and expected value are in RemovalCatchUpTest.matchesTheGoDaemonForTheSameRecords (Kotlin).
func TestRemovalFingerprintMatchesKotlin(t *testing.T) {
	got := digestOfRemovals([]removedRecord{
		{DeviceID: "bbbb-2", ClusterVersion: 1760000005000},
		{DeviceID: "aaaa-1", ClusterVersion: 1760000000000},
	})
	if got != "9bc86a65f2da0fbb" {
		t.Fatalf("fingerprint %q", got)
	}
}

// The whole path as the Mac drives it: the announce reply carries the fingerprint, the node reads the Mac's records
// and the ghost leaves the roster. The fake peer answers like the Kotlin server does.
func TestAnnounceReplyWithADifferentFingerprintDropsTheGhost(t *testing.T) {
	now := time.Now().UnixMilli()
	macRemovals := []removedRecord{{DeviceID: "oppo-0001", ClusterVersion: now - 1000, MembershipProtocol: membershipProtocol}}
	reads := 0
	mac := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v1/devices/merge":
			w.Header().Set("x-fileapex-removals", digestOfRemovals(macRemovals))
			writeJSON(w, http.StatusOK, []deviceRecord{})
		case "/api/v1/devices/removed":
			reads++
			writeJSON(w, http.StatusOK, macRemovals)
		default:
			http.NotFound(w, r)
		}
	}))
	defer mac.Close()
	host, port := splitHostPort(t, mac.URL)

	node := nodeWithPeers(t)
	peer := deviceRecord{DeviceID: "mac-1", DeviceName: "MacBook", LastKnownIP: host, Port: port, ClusterVersion: 100}
	node.mu.Lock()
	node.peers["mac-1"] = peer
	node.peers["oppo-0001"] = deviceRecord{DeviceID: "oppo-0001", DeviceName: "OPPO", LastKnownIP: "192.168.1.9", Port: 8080, ClusterVersion: now - 500000}
	node.mu.Unlock()

	state := node.selfState()
	node.announceRound(context.Background(), []deviceRecord{peer}, state, "SELF_METADATA", 2*time.Second)
	if reads != 1 {
		t.Fatalf("the Mac's records were read %d times", reads)
	}
	for _, p := range node.roster() {
		if p.DeviceID == "oppo-0001" {
			t.Fatal("the ghost is still in the roster")
		}
	}
	// A second round with the same fingerprint reads nothing.
	node.announceRound(context.Background(), []deviceRecord{peer}, state, "SELF_METADATA", 2*time.Second)
	if reads != 1 {
		t.Fatalf("a repeat round read again, total %d", reads)
	}
	// Once the node holds the same removal, the fingerprints match and nothing is read even on a new node start.
	if node.removalsDigest() != digestOfRemovals(macRemovals) {
		t.Fatal("after the read the node should hold the same removal as the Mac")
	}
}

func TestAnnounceReplyWithNoFingerprintReadsNothing(t *testing.T) {
	reads := 0
	old := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/v1/devices/removed" {
			reads++
		}
		writeJSON(w, http.StatusOK, []deviceRecord{})
	}))
	defer old.Close()
	host, port := splitHostPort(t, old.URL)
	node := nodeWithPeers(t)
	peer := deviceRecord{DeviceID: "old-1", DeviceName: "Old build", LastKnownIP: host, Port: port, ClusterVersion: 100}
	node.mu.Lock()
	node.peers["old-1"] = peer
	node.mu.Unlock()
	node.announceRound(context.Background(), []deviceRecord{peer}, node.selfState(), "SELF_METADATA", 2*time.Second)
	if reads != 0 {
		t.Fatalf("a peer without a fingerprint was read %d times", reads)
	}
}

// Docker to Docker: the answer to an announce carries the fingerprint, so two Docker nodes catch each other up.
func TestMergeAnswerCarriesTheFingerprintOnlyOverTLS(t *testing.T) {
	node := nodeWithHost(t)
	addTombstone(node, "gone-1", time.Now().UnixMilli())
	for _, withTLS := range []bool{false, true} {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/devices/merge?from=host-1", strings.NewReader(`{"eventKind":"SELF_METADATA","nodeStates":[],"removedDevices":[]}`))
		if withTLS {
			req.TLS = &tls.ConnectionState{}
		}
		rec := httptest.NewRecorder()
		node.handleMerge(rec, req)
		got := rec.Header().Get(removalsHeader)
		if withTLS && (got == "" || got == "0") {
			t.Fatalf("a proven peer should see the fingerprint, got %q", got)
		}
		if !withTLS && got != "" {
			t.Fatalf("plain HTTP must not carry it, got %q", got)
		}
	}
}
