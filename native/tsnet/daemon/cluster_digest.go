package main

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"sort"
	"strings"
)

// clusterDigestHeader carries one digest over the roster and the removal records on the health answer and on the
// merge answer to a proven peer, beside removalsHeader. The Kotlin nodes compute the same value (ClusterDigest).
const clusterDigestHeader = "X-FileApex-Cluster-Digest"

type clusterDigestRow struct {
	id    string
	stamp int64
	state byte
}

// digestOfCluster is `id:stamp:state` per row (state 'a' active, 'r' removed), sorted, SHA-256, first 16 hex
// characters; "0" when there are none.
func digestOfCluster(rows []clusterDigestRow) string {
	if len(rows) == 0 {
		return "0"
	}
	lines := make([]string, 0, len(rows))
	for _, r := range rows {
		lines = append(lines, fmt.Sprintf("%s:%d:%c\n", strings.TrimSpace(r.id), r.stamp, r.state))
	}
	sort.Strings(lines)
	sum := sha256.Sum256([]byte(strings.Join(lines, "")))
	return hex.EncodeToString(sum[:])[:16]
}

func (n *Node) clusterDigest() string {
	removals := n.windowedRemovals()
	n.mu.Lock()
	rows := make([]clusterDigestRow, 0, len(n.peers)+len(removals)+1)
	rows = append(rows, clusterDigestRow{n.identity.DeviceID, n.membership, 'a'})
	for _, peer := range n.peers {
		if !peer.IsRemoved {
			rows = append(rows, clusterDigestRow{peer.DeviceID, peer.ClusterVersion, 'a'})
		}
	}
	n.mu.Unlock()
	for _, r := range removals {
		rows = append(rows, clusterDigestRow{r.DeviceID, r.version(), 'r'})
	}
	return digestOfCluster(rows)
}
