package main

import (
	"bytes"
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
	"sync"
	"testing"
)

func resumeOffset(t *testing.T, base, target, tx string, backup bool, expected int64) map[string]any {
	t.Helper()
	q := url.Values{}
	q.Set("targetPath", target)
	q.Set("from", "host-1")
	q.Set("expectedSize", strconv.FormatInt(expected, 10))
	if tx != "" {
		q.Set("txId", tx)
	}
	if backup {
		q.Set("backup", "1")
	}
	resp, err := http.Get(base + "/api/v1/files/resume?" + q.Encode())
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

func postTxUpload(t *testing.T, base, target, tx string, backup bool, body []byte, offset int64) int {
	t.Helper()
	q := url.Values{}
	q.Set("targetPath", target)
	q.Set("from", "host-1")
	if tx != "" {
		q.Set("txId", tx)
	}
	if backup {
		q.Set("backup", "1")
	}
	if offset > 0 {
		q.Set("offset", strconv.FormatInt(offset, 10))
	}
	q.Set("totalSize", strconv.FormatInt(offset+int64(len(body)), 10))
	sum := sha256.Sum256(body)
	if offset == 0 {
		q.Set("sha256", hex.EncodeToString(sum[:]))
	}
	req, err := http.NewRequest(http.MethodPost, base+"/api/v1/files/upload?"+q.Encode(), bytes.NewReader(body))
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

func TestResumeWithBackupLooksAtTheFileBeingReplaced(t *testing.T) {
	node := nodeWithHost(t)
	server := httptest.NewServer(node.routes())
	defer server.Close()

	target := filepath.Join(node.inboxDir(), "report.txt")
	if err := os.WriteFile(target, bytes.Repeat([]byte{1}, 900), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(partPathForTx(target, "tx-backup"), bytes.Repeat([]byte{7}, 1500), 0o644); err != nil {
		t.Fatal(err)
	}

	backup := resumeOffset(t, server.URL, target, "tx-backup", true, 4000)
	if backup["offset"] != float64(1500) || backup["complete"] != false {
		t.Fatalf("backup resume = %+v", backup)
	}
	fresh := resumeOffset(t, server.URL, target, "tx-backup", false, 4000)
	if fresh["offset"] != float64(0) || fresh["complete"] != false {
		t.Fatalf("fresh resume = %+v", fresh)
	}
}

func TestSameNameUploadsWithDifferentTransactionsDoNotShareAPart(t *testing.T) {
	node := nodeWithHost(t)
	server := httptest.NewServer(node.routes())
	defer server.Close()

	target := filepath.Join(node.inboxDir(), "same.bin")
	first := bytes.Repeat([]byte{11}, 300_000)
	second := bytes.Repeat([]byte{77}, 300_000)

	statuses := make([]int, 2)
	var wg sync.WaitGroup
	for i, body := range [][]byte{first, second} {
		wg.Add(1)
		go func() {
			defer wg.Done()
			tx := "tx-a"
			if i == 1 {
				tx = "tx-b"
			}
			statuses[i] = postTxUpload(t, server.URL, target, tx, false, body, 0)
		}()
	}
	wg.Wait()
	for _, status := range statuses {
		if status != http.StatusCreated {
			t.Fatalf("statuses = %v", statuses)
		}
	}
	got := map[string]bool{}
	for _, name := range []string{"same.bin", "same (1).bin"} {
		body, err := os.ReadFile(filepath.Join(node.inboxDir(), name))
		if err != nil {
			t.Fatal(err)
		}
		got[string(body)] = true
	}
	if !got[string(first)] || !got[string(second)] {
		t.Fatalf("arrived contents do not match both uploads")
	}
}

func TestPartPathForTxStaysBesideTheTarget(t *testing.T) {
	target := filepath.Join(string(filepath.Separator), "inbox", "photo.jpg")
	hostile := partPathForTx(target, "../../evil")
	if filepath.Dir(hostile) != filepath.Dir(target) {
		t.Fatalf("hostile part path escaped: %s", hostile)
	}
	if partPathForTx(target, "") != target+partSuffix {
		t.Fatalf("no transaction must keep the shared part path")
	}
	if partPathForTx(target, "tx-a") == partPathForTx(target, "tx-b") {
		t.Fatalf("different transactions share a part path")
	}
}
