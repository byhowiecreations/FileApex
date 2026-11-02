package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
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
	raw := `FA_PAIR:{"deviceName":"Phone","ipAddress":"192.168.1.20","port":8080,"timestamp":10,"deviceId":"host-1","pinRequired":false,"tlsPin":"AABB","tlsPort":8081}`
	beacon, ok := parseBeacon(raw)
	if !ok {
		t.Fatal("expected beacon")
	}
	if beacon.TLSPin != "aabb" || beacon.TLSPort != 8081 || beacon.IPAddress != "192.168.1.20" || beacon.Port != 8080 {
		t.Fatalf("beacon = %+v", beacon)
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
	shot, err := node.resolvePath("@inbox/shot.png", false)
	if err != nil {
		t.Fatal(err)
	}
	if shot != filepath.Join(inbox, "shot.png") {
		t.Fatalf("single file landed at %s", shot)
	}
	nested, err := node.resolvePath(`@inbox\Album\a.jpg`, false)
	if err != nil {
		t.Fatal(err)
	}
	if nested != filepath.Join(inbox, "Album", `a.jpg`) && nested != filepath.Join(inbox, `Album\a.jpg`) {
		t.Fatalf("folder landed at %s", nested)
	}
	if _, err := node.resolvePath("@inbox/../cluster.json", false); err == nil {
		t.Fatal("climbing out of the receive folder was allowed")
	}
	if _, err := node.resolvePath("@inbox", false); err == nil {
		t.Fatal("naming the receive folder itself as a file was allowed")
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

func TestUnraidKernelDetection(t *testing.T) {
	if !unraidKernel("Linux version 6.12.24-Unraid (root@tower)") {
		t.Fatal("expected Unraid kernel to be recognized")
	}
	if unraidKernel("Linux version 6.1.0-21-amd64") {
		t.Fatal("debian kernel was treated as Unraid")
	}
	if unraidKernel("") {
		t.Fatal("empty release was treated as Unraid")
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

func uploadWithHash(t *testing.T, node *Node, name, body, sum string) (int, string) {
	t.Helper()
	server := httptest.NewServer(node.routes())
	t.Cleanup(server.Close)
	q := url.Values{}
	q.Set("targetPath", "@inbox/"+name)
	q.Set("from", "host-1")
	q.Set("totalSize", strconv.Itoa(len(body)))
	if sum != "" {
		q.Set("sha256", sum)
	}
	resp, err := http.Post(server.URL+"/api/v1/files/upload?"+q.Encode(), "application/octet-stream", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	got, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, strings.TrimSpace(string(got))
}

func nodeWithHost(t *testing.T) *Node {
	t.Helper()
	node, err := newNode(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	node.mu.Lock()
	node.peers["host-1"] = deviceRecord{DeviceID: "host-1", DeviceName: "Phone", LastKnownIP: "192.168.1.2", Port: 8080}
	node.mu.Unlock()
	return node
}

func TestUploadWithTheRightWholeFileHashIsStored(t *testing.T) {
	node := nodeWithHost(t)
	sum := sha256.Sum256([]byte("hello world"))
	status, _ := uploadWithHash(t, node, "good.txt", "hello world", hex.EncodeToString(sum[:]))
	if status != http.StatusCreated {
		t.Fatalf("status %d", status)
	}
	got, err := os.ReadFile(filepath.Join(node.inboxDir(), "good.txt"))
	if err != nil || string(got) != "hello world" {
		t.Fatalf("stored %q (%v)", got, err)
	}
}

func TestUploadWithAWrongWholeFileHashIsRefusedAndLeavesNothing(t *testing.T) {
	node := nodeWithHost(t)
	other := sha256.Sum256([]byte("something else"))
	status, body := uploadWithHash(t, node, "bad.txt", "hello world", hex.EncodeToString(other[:]))
	if status != http.StatusBadRequest || body != "checksum_mismatch" {
		t.Fatalf("status %d body %q", status, body)
	}
	entries, _ := os.ReadDir(node.inboxDir())
	for _, e := range entries {
		if strings.HasPrefix(e.Name(), "bad.txt") {
			t.Fatalf("left behind: %s", e.Name())
		}
	}
}

func TestUploadWithoutAHashBehavesAsBefore(t *testing.T) {
	node := nodeWithHost(t)
	if status, _ := uploadWithHash(t, node, "plain.txt", "abc", ""); status != http.StatusCreated {
		t.Fatalf("status %d", status)
	}
}

func TestIdentityDoesNotTellAStrangerWhereTheFolderIs(t *testing.T) {
	node := nodeWithHost(t)
	server := httptest.NewServer(node.routes())
	t.Cleanup(server.Close)
	resp, err := http.Get(server.URL + "/api/v1/identity")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if strings.Contains(string(body), node.inboxDir()) || strings.Contains(string(body), "downloadsPath") {
		t.Fatalf("identity leaked the folder: %s", body)
	}
	var seen nodeState
	if err := json.Unmarshal(body, &seen); err != nil {
		t.Fatal(err)
	}
	if seen.DeviceMake != "" || seen.DeviceModel != "" || seen.Platform != "" || seen.OS != "" || seen.IPAddress != "" {
		t.Fatalf("identity told a stranger about the device: %+v", seen)
	}
	if seen.DeviceID == "" || seen.PublicKey == "" || seen.TLSPin == "" {
		t.Fatalf("identity dropped what pairing needs: %+v", seen)
	}
}
