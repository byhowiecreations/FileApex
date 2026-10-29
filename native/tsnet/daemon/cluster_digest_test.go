package main

import (
	"crypto/tls"
	"net/http"
	"net/http/httptest"
	"testing"
)

// The same lines as the Kotlin ClusterDigestTest: a:5:a, b:7:r, self:9:a.
func TestClusterDigestMatchesTheKotlinVector(t *testing.T) {
	rows := []clusterDigestRow{{"self", 9, 'a'}, {"b", 7, 'r'}, {"a", 5, 'a'}}
	if got := digestOfCluster(rows); got != "a53152282229ef1e" {
		t.Fatalf("digest = %s", got)
	}
	if digestOfCluster(nil) != "0" {
		t.Fatal("no rows must give 0")
	}
}

func TestClusterDigestCoversThisNodeItsPeersAndItsRemovals(t *testing.T) {
	a := nodeWithPeers(t, "p1")
	b := nodeWithPeers(t, "p1")
	if a.clusterDigest() == "" || a.clusterDigest() == "0" {
		t.Fatal("a node always holds itself")
	}
	b.mu.Lock()
	b.peers["p2"] = deviceRecord{DeviceID: "p2", ClusterVersion: 5}
	b.mu.Unlock()
	if a.clusterDigest() == b.clusterDigest() {
		t.Fatal("a different roster must move the digest")
	}
}

func TestHealthCarriesTheDigestOnlyOverPinnedTLS(t *testing.T) {
	node := nodeWithPeers(t, "p1")
	req := httptest.NewRequest(http.MethodGet, "/api/v1/health", nil)
	req.TLS = &tls.ConnectionState{}
	rec := httptest.NewRecorder()
	node.handleHealth(rec, req)
	if rec.Header().Get(clusterDigestHeader) != node.clusterDigest() {
		t.Fatal("proven peer must get the digest")
	}
	plain := httptest.NewRecorder()
	node.handleHealth(plain, httptest.NewRequest(http.MethodGet, "/api/v1/health", nil))
	if plain.Header().Get(clusterDigestHeader) != "" {
		t.Fatal("a stranger must not get the digest")
	}
}
