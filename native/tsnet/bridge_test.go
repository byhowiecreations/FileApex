package tsnetbridge

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"strings"
	"testing"
	"time"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/types/key"
)

func TestLogoutWhenNodeIsDown(t *testing.T) {
	if err := Logout(); err == nil {
		t.Fatal("logout succeeded with no node")
	}
}

func TestTailnetDestinations(t *testing.T) {
	allowed := []string{"100.64.0.1", "100.127.255.254", "fd7a:115c:a1e0::1", "fd7a:115c:a1e0:ffff::abcd"}
	for _, host := range allowed {
		dest, ok := tailnetDestination(host, 80)
		if !ok || dest == "" {
			t.Fatalf("%s was rejected", host)
		}
	}
	rejected := []string{"8.8.8.8", "10.0.0.1", "192.168.1.1", "127.0.0.1", "100.63.255.255", "100.128.0.1", "fd7a:115c:a1e1::1", "hostname.tailnet"}
	for _, host := range rejected {
		if _, ok := tailnetDestination(host, 80); ok {
			t.Fatalf("%s was accepted", host)
		}
	}
	if _, ok := tailnetDestination("100.64.0.1", 0); ok {
		t.Fatal("port 0 was accepted")
	}
}

func TestRemoteTailnetGate(t *testing.T) {
	if !remoteIsTailnet(&net.TCPAddr{IP: net.ParseIP("100.64.1.2"), Port: 4242}) {
		t.Fatal("tailnet peer was rejected")
	}
	if remoteIsTailnet(&net.TCPAddr{IP: net.ParseIP("127.0.0.1"), Port: 4242}) {
		t.Fatal("loopback peer was accepted")
	}
	if remoteIsTailnet(&net.TCPAddr{IP: net.ParseIP("192.168.1.9"), Port: 80}) {
		t.Fatal("lan peer was accepted")
	}
	parsed, err := netip.ParseAddr("fd7a:115c:a1e0::8")
	if err != nil {
		t.Fatal(err)
	}
	if !remoteIsTailnet(&net.TCPAddr{IP: parsed.AsSlice(), Port: 9}) {
		t.Fatal("tailnet ipv6 was rejected")
	}
}

func TestDialTokenRequiredBeforeAnyDial(t *testing.T) {
	token := "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	go func() {
		_, _ = io.WriteString(client, "wrong-token\nGET / HTTP/1.1\r\n")
	}()
	if authorizeDial(server, token, "100.64.0.1:80") {
		t.Fatal("wrong token was accepted")
	}
}

func TestDialTokenLeavesTheFollowingBytes(t *testing.T) {
	token := "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	go func() {
		_, _ = io.WriteString(client, token+"\nGET /")
	}()
	if !authorizeDial(server, token, "100.64.0.1:80") {
		t.Fatal("token was rejected")
	}
	buf := make([]byte, 5)
	_ = server.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, err := io.ReadFull(server, buf); err != nil {
		t.Fatal(err)
	}
	if string(buf) != "GET /" {
		t.Fatalf("following bytes = %q", buf)
	}
}

func TestNonTailnetDestIsRejectedBeforeDial(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	if authorizeDial(server, "anything", "8.8.8.8:443") {
		t.Fatal("public destination was accepted")
	}
}

func TestLoopbackListenerIsRandomLoopback(t *testing.T) {
	ln, err := loopbackListener()
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	tcp := ln.Addr().(*net.TCPAddr)
	if !tcp.IP.IsLoopback() || tcp.Port == 0 {
		t.Fatalf("listener = %s", tcp)
	}
	if tcp.IP.String() != "127.0.0.1" {
		t.Fatalf("bound %s", tcp.IP)
	}
}

func TestPeersFromStatusSkipsSelfAndKeepsTailnetIPv4(t *testing.T) {
	v4 := netip.MustParseAddr("100.64.8.8")
	v6 := netip.MustParseAddr("fd7a:115c:a1e0::9")
	var id key.NodePublic
	status := &ipnstate.Status{
		Self: &ipnstate.PeerStatus{
			HostName:     "fileapex-self",
			TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.1")},
		},
		Peer: map[key.NodePublic]*ipnstate.PeerStatus{
			id: {
				HostName:     "fileapex-bbbb",
				DNSName:      "fileapex-bbbb.tail.ts.net.",
				TailscaleIPs: []netip.Addr{v6, v4},
				Online:       true,
			},
		},
	}
	peers := peersFromStatus(status)
	if len(peers) != 1 {
		t.Fatalf("peers = %d", len(peers))
	}
	if peers[0].HostName != "fileapex-bbbb" || peers[0].IPv4 != "100.64.8.8" || !peers[0].Online {
		t.Fatalf("%+v", peers[0])
	}
	raw, err := json.Marshal(peersFromStatus(nil))
	if err != nil || string(raw) != "[]" {
		t.Fatalf("empty peers = %s %v", raw, err)
	}
}

func TestKeyExpiryComesFromStatus(t *testing.T) {
	if statusKeyExpired(nil) {
		t.Fatal("nil status")
	}
	past := time.Now().Add(-time.Minute)
	expired := &ipnstate.Status{
		BackendState: "NeedsLogin",
		Self:         &ipnstate.PeerStatus{Expired: true, KeyExpiry: &past},
	}
	report := reportFromStatus(expired)
	if !report.keyExpired || report.backend != "NeedsLogin" {
		t.Fatalf("report = %+v", report)
	}
	future := time.Now().Add(time.Hour)
	running := &ipnstate.Status{
		BackendState: "Running",
		Self:         &ipnstate.PeerStatus{KeyExpiry: &future},
	}
	if statusKeyExpired(running) {
		t.Fatal("future expiry was treated as expired")
	}
}

func TestAuthorizedPeerBytesReachTheUserspaceDial(t *testing.T) {
	ln, err := loopbackListener()
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	token := strings.Repeat("ab", 32)
	dest := "100.64.1.2:8080"
	dialed := make(chan net.Conn, 1)
	go acceptLoopback(ln, dest, token, func(ctx context.Context, network, addr string) (net.Conn, error) {
		if ctx == nil || network != "tcp" || addr != dest {
			return nil, fmt.Errorf("dial %s %s", network, addr)
		}
		local, remote := net.Pipe()
		dialed <- remote
		return local, nil
	})
	conn, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if _, err := io.WriteString(conn, token+"\nGET /api/v1/health HTTP/1.1\r\n\r\n"); err != nil {
		t.Fatal(err)
	}
	select {
	case upstream := <-dialed:
		defer upstream.Close()
		buf := make([]byte, 64)
		_ = upstream.SetReadDeadline(time.Now().Add(2 * time.Second))
		n, err := io.ReadAtLeast(upstream, buf, len("GET /"))
		if err != nil {
			t.Fatal(err)
		}
		if !strings.HasPrefix(string(buf[:n]), "GET /api/v1/health") {
			t.Fatalf("dial saw %q", buf[:n])
		}
	case <-time.After(3 * time.Second):
		t.Fatal("peer bytes did not reach the userspace dial")
	}
}

func TestLanAndBadTokenNeverDial(t *testing.T) {
	token := strings.Repeat("cd", 32)
	cases := []struct {
		name string
		dest string
		line string
	}{
		{"lan", "192.168.1.9:8080", token + "\nGET /"},
		{"bad token", "100.64.1.2:8080", "nope\nGET /"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			ln, err := loopbackListener()
			if err != nil {
				t.Fatal(err)
			}
			defer ln.Close()
			dialed := make(chan struct{}, 1)
			go acceptLoopback(ln, tc.dest, token, func(context.Context, string, string) (net.Conn, error) {
				dialed <- struct{}{}
				return nil, fmt.Errorf("should not dial")
			})
			conn, err := net.Dial("tcp", ln.Addr().String())
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			_, _ = io.WriteString(conn, tc.line)
			_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
			_, _ = conn.Read(make([]byte, 1))
			select {
			case <-dialed:
				t.Fatal("dial was used")
			default:
			}
		})
	}
}

func TestLoginURLIsReadyAsSoonAsItExists(t *testing.T) {
	authURL.Store("")
	if loginURLPresent(&ipnstate.Status{}) {
		t.Fatal("empty status")
	}
	if !loginURLPresent(&ipnstate.Status{AuthURL: "https://login.tailscale.com/a/abc"}) {
		t.Fatal("status url")
	}
	authURL.Store("https://login.tailscale.com/a/from-log")
	if !loginURLPresent(&ipnstate.Status{}) {
		t.Fatal("stored url")
	}
	authURL.Store("")
}

func TestAuthURLIsTakenFromTheLoginLog(t *testing.T) {
	authURL.Store("")
	captureAuthURL("noise")
	if AuthURL() != "" {
		t.Fatal("plain log became a login url")
	}
	captureAuthURL("To start this tsnet server, restart with TS_AUTHKEY set, or go to: https://login.tailscale.com/a/abc123")
	if AuthURL() != "https://login.tailscale.com/a/abc123" {
		t.Fatalf("url = %q", AuthURL())
	}
	authURL.Store("")
}

func TestClearStateRefusesARunningNode(t *testing.T) {
	mu.Lock()
	previous := current
	current = &runningNode{}
	mu.Unlock()
	t.Cleanup(func() {
		mu.Lock()
		current = previous
		mu.Unlock()
	})
	if err := ClearState(t.TempDir()); err == nil {
		t.Fatal("state was deleted while a node was current")
	}
}

func TestClearStateDeletesEnrollmentFiles(t *testing.T) {
	dir := t.TempDir()
	if err := prepareNodeStorage(dir); err != nil {
		t.Fatal(err)
	}
	if err := ClearState(dir); err != nil {
		t.Fatal(err)
	}
	entries, err := osRead(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 0 {
		t.Fatalf("state dir still has %d entries", len(entries))
	}
}

func osRead(dir string) ([]string, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}
	names := make([]string, 0, len(entries))
	for _, entry := range entries {
		names = append(names, entry.Name())
	}
	return names, nil
}
