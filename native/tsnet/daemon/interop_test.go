package main

import (
	"encoding/json"
	"fmt"
	"os"
	"strconv"
	"testing"
	"time"
)

// TestInteropHold starts a daemon node for the Kotlin and Swift clients to talk to. It does nothing
// unless FA_INTEROP_PEER_ID is set, and never touches the network beyond its own two ports.
func TestInteropHold(t *testing.T) {
	peerID := os.Getenv("FA_INTEROP_PEER_ID")
	if peerID == "" {
		t.Skip("interop helper")
	}
	n, err := newNode(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	n.port = 18080
	n.allowLoopback = true
	n.peers[peerID] = deviceRecord{
		DeviceID: peerID, DeviceName: "Interop peer", LastKnownIP: "127.0.0.1", Port: 1,
		PublicKey: os.Getenv("FA_INTEROP_PEER_PUB"), ClusterVersion: time.Now().UnixMilli(),
	}
	if err := n.serve(); err != nil {
		t.Fatal(err)
	}
	info, _ := json.Marshal(map[string]any{"deviceId": n.deviceID(), "http": n.listenPort(), "tls": n.tlsPort(), "pin": n.tlsPinValue(), "inbox": n.inboxDir()})
	fmt.Printf("INTEROP_READY %s\n", info)
	hold, _ := strconv.Atoi(os.Getenv("FA_INTEROP_HOLD_SECONDS"))
	time.Sleep(time.Duration(hold) * time.Second)
	n.shutdown(time.Second)
}
