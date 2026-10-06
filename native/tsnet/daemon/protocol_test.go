package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
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

func TestFingerprintMatchesKotlin(t *testing.T) {
	if got := fingerprint("fileapex-test"); got != "e84efb9f89616366" {
		t.Fatalf("fingerprint = %s", got)
	}
}

func TestParseBeacon(t *testing.T) {
	raw := `FA_PAIR:{"deviceName":"Phone","ipAddress":"192.168.1.20","port":8080,"pairingCode":"522457","timestamp":10,"deviceId":"host-1","pinRequired":false}`
	beacon, ok := parseBeacon(raw)
	if !ok {
		t.Fatal("expected beacon")
	}
	if beacon.PairingCode != "522457" || beacon.IPAddress != "192.168.1.20" || beacon.Port != 8080 {
		t.Fatalf("beacon = %+v", beacon)
	}
	dashed := `{"deviceName":"Phone","ipAddress":"10.0.0.8","port":8080,"pairingCode":"522-457","timestamp":10,"deviceId":"host-1"}`
	beacon, ok = parseBeacon(dashed)
	if !ok || beacon.PairingCode != "522457" {
		t.Fatalf("dashed beacon = %+v ok=%v", beacon, ok)
	}
	if _, ok := parseBeacon("FA_PAIR:{}"); ok {
		t.Fatal("empty beacon was accepted")
	}
	if _, ok := parseBeacon("not-a-beacon"); ok {
		t.Fatal("noise was accepted")
	}
}

func TestResolvePathStaysInInbox(t *testing.T) {
	node, err := newNode(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	inbox, err := filepath.EvalSymlinks(node.inboxDir())
	if err != nil {
		t.Fatal(err)
	}
	got, err := node.resolvePath(filepath.Join(inbox, "photos", "a.jpg"), false)
	if err != nil {
		t.Fatal(err)
	}
	if !pathInside(inbox, got) {
		t.Fatalf("resolved %s outside %s", got, inbox)
	}
	if _, err := node.resolvePath(filepath.Join(inbox, "..", "cluster.json"), false); err == nil {
		t.Fatal("path escape was allowed")
	}
	if _, err := node.resolvePath(inbox, false); err == nil {
		t.Fatal("upload onto the inbox directory itself was allowed")
	}
	shot, err := node.resolvePath(filepath.Join(inbox, "Downloads", "FileApex", "shot.png"), false)
	if err != nil {
		t.Fatal(err)
	}
	if shot != filepath.Join(inbox, "shot.png") {
		t.Fatalf("single file landed at %s", shot)
	}
	nested, err := node.resolvePath(filepath.Join(inbox, "Download", "FileApex", "Album", "a.jpg"), false)
	if err != nil {
		t.Fatal(err)
	}
	if nested != filepath.Join(inbox, "Album", "a.jpg") {
		t.Fatalf("folder landed at %s", nested)
	}
}

func TestSelfRemovalClearsRoster(t *testing.T) {
	node, err := newNode(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	node.mu.Lock()
	node.joined = true
	node.membership = 100
	node.peers["host-1"] = deviceRecord{
		DeviceID:       "host-1",
		DeviceName:     "Phone",
		LastKnownIP:    "192.168.1.2",
		Port:           8080,
		ClusterVersion: 100,
	}
	node.mu.Unlock()
	removedAt := int64(250)
	left := node.applySync(clusterSync{
		EventKind: "REMOVAL",
		RemovedDevices: []removedRecord{{
			DeviceID:           node.deviceID(),
			ClusterVersion:     250,
			RemovedAt:          &removedAt,
			MembershipProtocol: membershipProtocol,
		}},
	})
	if !left || node.isJoined() {
		t.Fatalf("left=%v joined=%v", left, node.isJoined())
	}
	if len(node.roster()) != 0 {
		t.Fatalf("peers remain: %+v", node.roster())
	}
	if !node.wasRemoved() || node.removalVersion() != 250 {
		t.Fatalf("removed=%v version=%d", node.wasRemoved(), node.removalVersion())
	}
}

func TestRemovalBlocksRejoin(t *testing.T) {
	dir := t.TempDir()
	node, err := newNode(dir)
	if err != nil {
		t.Fatal(err)
	}
	node.mu.Lock()
	node.joined = true
	node.membership = 100
	node.mu.Unlock()
	removedAt := int64(900)
	if !node.applySync(clusterSync{
		EventKind: "REMOVAL",
		RemovedDevices: []removedRecord{{
			DeviceID:           node.deviceID(),
			ClusterVersion:     900,
			RemovedAt:          &removedAt,
			MembershipProtocol: membershipProtocol,
		}},
	}) {
		t.Fatal("expected removal")
	}
	reloaded, err := newNode(dir)
	if err != nil {
		t.Fatal(err)
	}
	if reloaded.deviceID() != node.deviceID() || reloaded.isJoined() || !reloaded.wasRemoved() || reloaded.removalVersion() != 900 {
		t.Fatalf("reloaded joined=%v removed=%v version=%d", reloaded.isJoined(), reloaded.wasRemoved(), reloaded.removalVersion())
	}
	reloaded.applySync(clusterSync{
		EventKind: "PAIRING_INTRO",
		NodeStates: []nodeState{{
			DeviceID:           reloaded.deviceID(),
			DeviceName:         "Docker",
			MembershipProtocol: membershipProtocol,
			MembershipVersion:  50_000,
		}},
	})
	reloaded.markJoined()
	if reloaded.isJoined() || reloaded.hasMembership() {
		t.Fatal("removed node rejoined from a later intro")
	}
}

func TestPickDockerName(t *testing.T) {
	if got := pickDockerName(nil); got != "Docker" {
		t.Fatalf("empty = %s", got)
	}
	if got := pickDockerName(map[string]struct{}{"docker": {}}); got != "Docker2" {
		t.Fatalf("one = %s", got)
	}
	if got := pickDockerName(map[string]struct{}{"docker": {}, "docker2": {}}); got != "Docker3" {
		t.Fatalf("two = %s", got)
	}
}

func TestLegacyAutoNameBecomesDocker(t *testing.T) {
	dir := t.TempDir()
	if err := writeJSONFile(filepath.Join(dir, "identity.json"), identityFile{
		DeviceID:   "dev-legacy",
		DeviceName: "fileapex-omv",
	}); err != nil {
		t.Fatal(err)
	}
	node, err := newNode(dir)
	if err != nil {
		t.Fatal(err)
	}
	if node.deviceName() != "Docker" || !node.usesAutoName() {
		t.Fatalf("name=%s auto=%v", node.deviceName(), node.usesAutoName())
	}

	kept := t.TempDir()
	if err := writeJSONFile(filepath.Join(kept, "identity.json"), identityFile{
		DeviceID:   "dev-named",
		DeviceName: "OMV Backup",
	}); err != nil {
		t.Fatal(err)
	}
	named, err := newNode(kept)
	if err != nil {
		t.Fatal(err)
	}
	if named.deviceName() != "OMV Backup" || named.usesAutoName() {
		t.Fatalf("name=%s auto=%v", named.deviceName(), named.usesAutoName())
	}
}

func TestTailscaleConfigSurvivesRosterSave(t *testing.T) {
	node, err := newNode(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	node.saveTailscale("auth_key", "tskey-auth-test")
	node.mu.Lock()
	node.joined = true
	node.persistLocked()
	node.mu.Unlock()
	cfg, err := loadConfig(node.configPath)
	if err != nil {
		t.Fatal(err)
	}
	mode, key := configuredTailscale(cfg)
	if mode != "auth_key" || key != "tskey-auth-test" || !cfg.Joined {
		t.Fatalf("cfg=%+v mode=%s key=%s", cfg, mode, key)
	}
}

func TestHandshakeRosterAndPairedUpload(t *testing.T) {
	node, err := newNode(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	node.port = 0
	node.forceAdvertise = "192.168.50.20"
	node.allowLoopback = true
	node.announceTimeout = 200 * time.Millisecond
	node.dialTimeout = 200 * time.Millisecond
	if err := node.serve(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { node.shutdown(time.Second) })
	waitForHealth(t, node.listenPort())

	var gotCode string
	var gotDevice deviceRecord
	broadcaster := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v1/identity":
			_ = json.NewEncoder(w).Encode(nodeState{
				DeviceID:           "host-1",
				DeviceName:         "Phone",
				IPAddress:          "192.168.1.20",
				Port:               8080,
				Platform:           "android",
				OS:                 "android",
				RootPath:           "/storage/emulated/0",
				MembershipVersion:  1000,
				MembershipProtocol: membershipProtocol,
				PublicKeyHash:      "abc",
			})
		case "/api/v1/pairing/respond":
			gotCode = r.URL.Query().Get("code")
			if err := json.NewDecoder(r.Body).Decode(&gotDevice); err != nil {
				http.Error(w, err.Error(), http.StatusBadRequest)
				return
			}
			w.WriteHeader(http.StatusCreated)
			go func() {
				time.Sleep(30 * time.Millisecond)
				removedAt := int64(50)
				seed := clusterSync{
					EventKind: "PAIRING_INTRO",
					NodeStates: []nodeState{
						{
							DeviceID:           "host-1",
							DeviceName:         "Phone",
							IPAddress:          "192.168.1.20",
							Port:               8080,
							MembershipVersion:  1000,
							MembershipProtocol: membershipProtocol,
							RootPath:           "/storage/emulated/0",
						},
						{
							DeviceID:           node.deviceID(),
							DeviceName:         node.deviceName(),
							IPAddress:          "192.168.50.20",
							Port:               node.listenPort(),
							MembershipVersion:  5000,
							MembershipProtocol: membershipProtocol,
							RootPath:           node.inboxDir(),
							DownloadsPath:      node.inboxDir(),
						},
						{
							DeviceID:           "peer-2",
							DeviceName:         "Laptop",
							IPAddress:          "192.168.1.30",
							Port:               8080,
							MembershipVersion:  4000,
							MembershipProtocol: membershipProtocol,
							RootPath:           "/Users/laptop",
						},
					},
					RemovedDevices: []removedRecord{{
						DeviceID:           "old-peer",
						ClusterVersion:     50,
						RemovedAt:          &removedAt,
						MembershipProtocol: membershipProtocol,
					}},
				}
				payload, _ := json.Marshal(seed)
				req, err := http.NewRequest(http.MethodPost, "http://127.0.0.1:"+strconv.Itoa(node.listenPort())+"/api/v1/devices/merge?from=host-1", strings.NewReader(string(payload)))
				if err != nil {
					return
				}
				req.Header.Set("Content-Type", "application/json")
				resp, err := http.DefaultClient.Do(req)
				if err == nil {
					resp.Body.Close()
				}
			}()
		case "/api/v1/devices":
			_ = json.NewEncoder(w).Encode([]deviceRecord{
				{DeviceID: "peer-2", DeviceName: "Laptop", LastKnownIP: "192.168.1.30", Port: 8080, RootPath: "/Users/laptop", ClusterVersion: 4000},
				{DeviceID: node.deviceID(), DeviceName: node.deviceName(), LastKnownIP: "192.168.50.20", Port: node.listenPort(), ClusterVersion: 5000},
			})
		case "/api/v1/devices/merge":
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte("[]"))
		default:
			http.NotFound(w, r)
		}
	}))
	defer broadcaster.Close()
	hostURL, err := url.Parse(broadcaster.URL)
	if err != nil {
		t.Fatal(err)
	}
	port, _ := strconv.Atoi(hostURL.Port())
	err = node.handshake(context.Background(), pairingBeacon{
		DeviceName:  "Phone",
		IPAddress:   hostURL.Hostname(),
		Port:        port,
		PairingCode: "123456",
		DeviceID:    "host-1",
	}, "")
	if err != nil {
		t.Fatal(err)
	}
	if gotCode != "123456" {
		t.Fatalf("code = %q", gotCode)
	}
	if gotDevice.DeviceID != node.deviceID() || gotDevice.Platform != "linux" || gotDevice.Port != node.listenPort() {
		t.Fatalf("pairing body = %+v", gotDevice)
	}
	if gotDevice.RootPath != node.inboxDir() {
		t.Fatalf("root = %s want %s", gotDevice.RootPath, node.inboxDir())
	}
	node.mu.Lock()
	membership := node.membership
	node.mu.Unlock()
	if membership != 5000 {
		t.Fatalf("membership = %d", membership)
	}
	if !node.isJoined() {
		t.Fatal("not joined")
	}
	peers := map[string]deviceRecord{}
	for _, peer := range node.roster() {
		peers[peer.DeviceID] = peer
	}
	if _, ok := peers["host-1"]; !ok {
		t.Fatalf("missing host: %+v", node.roster())
	}
	if _, ok := peers["peer-2"]; !ok {
		t.Fatalf("missing laptop: %+v", node.roster())
	}
	if _, ok := peers["old-peer"]; ok {
		t.Fatal("tombstoned peer was kept")
	}
	if peers["host-1"].LastKnownIP != "192.168.1.20" {
		t.Fatalf("host ip = %s", peers["host-1"].LastKnownIP)
	}

	target := filepath.Join(node.inboxDir(), "backup.txt")
	stranger := postUpload(t, node.listenPort(), target, "stranger", "nope", 0, 0)
	if stranger != http.StatusForbidden {
		t.Fatalf("stranger status = %d", stranger)
	}
	escape := postUpload(t, node.listenPort(), filepath.Join(node.inboxDir(), "..", "cluster.json"), "host-1", "x", 0, 0)
	if escape != http.StatusForbidden {
		t.Fatalf("escape status = %d", escape)
	}
	status := postUpload(t, node.listenPort(), target, "host-1", "hello", 0, 0)
	if status != http.StatusCreated {
		t.Fatalf("upload status = %d", status)
	}
	body, err := os.ReadFile(target)
	if err != nil {
		t.Fatal(err)
	}
	if string(body) != "hello" {
		t.Fatalf("file = %q", body)
	}

	partTarget := filepath.Join(node.inboxDir(), "partial.bin")
	if got := postUpload(t, node.listenPort(), partTarget, "peer-2", "ab", 0, 4); got != http.StatusBadRequest {
		t.Fatalf("partial status = %d", got)
	}
	resume := getJSON(t, "http://127.0.0.1:"+strconv.Itoa(node.listenPort())+"/api/v1/files/resume?from=peer-2&expectedSize=4&targetPath="+url.QueryEscape(partTarget))
	if resume["offset"] != float64(2) || resume["complete"] != false {
		t.Fatalf("resume = %+v", resume)
	}
	if got := postUpload(t, node.listenPort(), partTarget, "peer-2", "cd", 2, 4); got != http.StatusCreated {
		t.Fatalf("resume upload status = %d", got)
	}
	body, err = os.ReadFile(partTarget)
	if err != nil {
		t.Fatal(err)
	}
	if string(body) != "abcd" {
		t.Fatalf("partial file = %q", body)
	}
	if _, err := os.Stat(partTarget + partSuffix); !os.IsNotExist(err) {
		t.Fatalf("part file still present: %v", err)
	}

	idResp, err := http.Get("http://127.0.0.1:" + strconv.Itoa(node.listenPort()) + "/api/v1/identity")
	if err != nil {
		t.Fatal(err)
	}
	defer idResp.Body.Close()
	var state nodeState
	if err := json.NewDecoder(idResp.Body).Decode(&state); err != nil {
		t.Fatal(err)
	}
	if state.DownloadsPath != node.inboxDir() || state.MembershipVersion != 5000 || state.Platform != "linux" {
		t.Fatalf("identity = %+v", state)
	}
}

func postUpload(t *testing.T, port int, target, from, body string, offset, total int64) int {
	t.Helper()
	q := url.Values{}
	q.Set("targetPath", target)
	q.Set("from", from)
	if offset > 0 {
		q.Set("offset", strconv.FormatInt(offset, 10))
	}
	if total > 0 {
		q.Set("totalSize", strconv.FormatInt(total, 10))
	}
	req, err := http.NewRequest(http.MethodPost, "http://127.0.0.1:"+strconv.Itoa(port)+"/api/v1/files/upload?"+q.Encode(), strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, resp.Body)
	return resp.StatusCode
}

func getJSON(t *testing.T, rawURL string) map[string]any {
	t.Helper()
	resp, err := http.Get(rawURL)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		t.Fatal(err)
	}
	return out
}

func TestRosterResponseIsNotChunked(t *testing.T) {
	node, err := newNode(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	node.mu.Lock()
	for i := 0; i < 40; i++ {
		id := fmt.Sprintf("peer-%02d", i)
		node.peers[id] = deviceRecord{
			DeviceID:      id,
			DeviceName:    "Phone " + id + " with a long display name",
			LastKnownIP:   "192.168.1." + strconv.Itoa(i+1),
			Port:          8080,
			PublicKeyHash: "e84efb9f89616366",
			Platform:      "android",
			OS:            "android",
		}
	}
	node.mu.Unlock()
	body, err := json.Marshal(node.roster())
	if err != nil {
		t.Fatal(err)
	}
	if len(body) <= 2048 {
		t.Fatalf("roster JSON is %d bytes; Go would not chunk a small body", len(body))
	}
	server := httptest.NewServer(node.routes())
	t.Cleanup(server.Close)
	resp, err := http.Get(server.URL + "/api/v1/devices")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	got, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status %d body %s", resp.StatusCode, got)
	}
	if resp.ContentLength != int64(len(got)) {
		t.Fatalf("content-length %d, body %d, transfer-encoding %q", resp.ContentLength, len(got), resp.TransferEncoding)
	}
	if strings.Contains(strings.ToLower(resp.Header.Get("Transfer-Encoding")), "chunked") {
		t.Fatal("roster response was chunked")
	}
}

func waitForHealth(t *testing.T, port int) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	var last error
	for time.Now().Before(deadline) {
		resp, err := http.Get("http://127.0.0.1:" + strconv.Itoa(port) + "/api/v1/health")
		if err == nil {
			resp.Body.Close()
			if resp.StatusCode == http.StatusOK {
				return
			}
		}
		last = err
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("server did not become ready: %v", last)
}
