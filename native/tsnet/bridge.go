package tsnetbridge

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tsnet"
)

const (
	bringUpBudget   = 60 * time.Second
	loginStableFor  = 8 * time.Second
	dialTokenMaxLen = 80
	dialTokenWait   = 5 * time.Second
)

var (
	tailnetIPv4 = netip.MustParsePrefix("100.64.0.0/10")
	tailnetIPv6 = netip.MustParsePrefix("fd7a:115c:a1e0::/48")
)

type runningNode struct {
	server    *tsnet.Server
	cancel    context.CancelFunc
	ln        net.Listener
	loops     map[string]net.Listener
	token     string
	sharePort int
}

type bringUpReport struct {
	backend    string
	ipv4       string
	keyExpired bool
}

var (
	mu          sync.Mutex
	current     *runningNode
	lastReport  bringUpReport
	activeConns atomic.Int32
	authURL     atomic.Value
)

// Start brings up an in-process userspace node. It does not install a TUN device.
// sharePort is accepted on the tailnet and forwarded to the local FileApex server.
// Close is never run concurrently with Start: both take mu, and Close runs only
// after Start has returned or from the failure path inside Start.
func Start(authKey, dir, hostname string, sharePort int) (ip string, err error) {
	mu.Lock()
	defer mu.Unlock()
	var server *tsnet.Server
	defer func() {
		if rec := recover(); rec != nil {
			ip = ""
			err = fmt.Errorf("tsnet panic: %s", redact(fmt.Sprint(rec)))
			if server != nil && current != nil && current.server == server {
				stopLocked()
			} else {
				safeClose(server)
			}
		}
	}()
	stopLocked()
	lastReport = bringUpReport{}
	authURL.Store("")
	authKey = strings.TrimSpace(authKey)
	if sharePort <= 0 || sharePort > 65535 {
		return "", fmt.Errorf("invalid share port")
	}
	if err = prepareNodeStorage(dir); err != nil {
		return "", err
	}
	token, err := newDialToken()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithCancel(context.Background())
	server = &tsnet.Server{
		Dir:       dir,
		Hostname:  hostname,
		AuthKey:   authKey,
		Ephemeral: false,
		Logf:      redactLog,
		UserLogf:  captureAuthURL,
	}
	node := &runningNode{
		server:    server,
		cancel:    cancel,
		loops:     map[string]net.Listener{},
		token:     token,
		sharePort: sharePort,
	}
	current = node
	if err = server.Start(); err != nil {
		stopLocked()
		return "", fmt.Errorf("%s", redact(err.Error()))
	}
	status, waitErr := waitUntilUsable(ctx, server)
	rememberStatus(status)
	if lastReport.keyExpired || needsLogin(lastReport.backend) {
		go serveWhenRunning(ctx, server, node)
		return "", nil
	}
	if waitErr != nil {
		stopLocked()
		return "", fmt.Errorf("%s", redact(waitErr.Error()))
	}
	if lastReport.backend != "Running" {
		stopLocked()
		return "", fmt.Errorf("tailnet state %s", lastReport.backend)
	}
	ip = firstIPv4(status)
	if ip == "" {
		stopLocked()
		return "", fmt.Errorf("no tailnet address")
	}
	ln, listenErr := server.Listen("tcp", fmt.Sprintf(":%d", sharePort))
	if listenErr != nil {
		stopLocked()
		return "", fmt.Errorf("%s", redact(listenErr.Error()))
	}
	node.ln = ln
	go acceptToLocal(ctx, ln, sharePort)
	return ip, nil
}

// Logout expires this node at the coordination server.
// The local store stays until the caller stops the node and clears it.
func Logout() error {
	mu.Lock()
	var server *tsnet.Server
	if current != nil {
		server = current.server
	}
	mu.Unlock()
	if server == nil {
		return errNodeDown
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	client, err := server.LocalClient()
	if err != nil {
		return fmt.Errorf("%s", redact(err.Error()))
	}
	if err = client.Logout(ctx); err != nil {
		return fmt.Errorf("%s", redact(err.Error()))
	}
	return nil
}

var errNodeDown = errors.New("node is down")

// Stop closes the userspace node and waits until Close returns.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	stopLocked()
}

func stopLocked() {
	node := current
	current = nil
	if node == nil {
		return
	}
	node.cancel()
	authURL.Store("")
	lastReport = bringUpReport{}
	if node.ln != nil {
		_ = node.ln.Close()
	}
	for _, ln := range node.loops {
		_ = ln.Close()
	}
	safeClose(node.server)
}

// ClearState deletes the node directory after the node is fully stopped.
func ClearState(dir string) error {
	mu.Lock()
	defer mu.Unlock()
	if current != nil {
		return fmt.Errorf("node is running")
	}
	return removeStateDir(dir)
}

// SessionToken is the per-start dial secret. Empty when the node is down.
func SessionToken() string {
	mu.Lock()
	defer mu.Unlock()
	if current == nil {
		return ""
	}
	return current.token
}

// BackendState is the last ipnstate.Status.BackendState from bring-up.
func BackendState() string {
	mu.Lock()
	defer mu.Unlock()
	return lastReport.backend
}

// KeyExpired reports Status.Self.Expired or a KeyExpiry already in the past.
func KeyExpired() bool {
	mu.Lock()
	defer mu.Unlock()
	return lastReport.keyExpired
}

// AuthURL is the interactive login page. Empty once the node is running.
func AuthURL() string {
	value, _ := authURL.Load().(string)
	return value
}

// SelfIPv4 is the node's tailnet IPv4 after it reaches Running.
func SelfIPv4() string {
	mu.Lock()
	defer mu.Unlock()
	return lastReport.ipv4
}

// ActiveConnections is the number of spliced tailnet streams.
func ActiveConnections() int {
	return int(activeConns.Load())
}

type exportedPeer struct {
	HostName string `json:"hostName"`
	DNSName  string `json:"dnsName"`
	IPv4     string `json:"ipv4"`
	Online   bool   `json:"online"`
}

// Peers is the current tailnet peer list from LocalClient.Status.
// The local node is not included. A failure is not an empty tailnet.
func Peers() (string, error) {
	mu.Lock()
	node := current
	mu.Unlock()
	if node == nil || node.server == nil {
		return "[]", nil
	}
	client, err := node.server.LocalClient()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	status, err := client.Status(ctx)
	if err != nil || status == nil {
		if err == nil {
			err = fmt.Errorf("missing peer status")
		}
		return "", err
	}
	payload, err := json.Marshal(peersFromStatus(status))
	if err != nil {
		return "", err
	}
	return string(payload), nil
}

func peersFromStatus(status *ipnstate.Status) []exportedPeer {
	out := []exportedPeer{}
	if status == nil {
		return out
	}
	for _, peer := range status.Peer {
		if peer == nil {
			continue
		}
		out = append(out, exportedPeer{
			HostName: peer.HostName,
			DNSName:  peer.DNSName,
			IPv4:     firstAddrIPv4(peer.TailscaleIPs),
			Online:   peer.Online,
		})
	}
	sort.Slice(out, func(i, j int) bool {
		return out[i].HostName < out[j].HostName
	})
	return out
}

// Loopback listens on 127.0.0.1 and dials host:port through the userspace node.
// The caller must write the session token and a newline before any other bytes.
func Loopback(host string, port int) (int, error) {
	dest, ok := tailnetDestination(host, port)
	if !ok {
		return 0, fmt.Errorf("destination is not on the tailnet")
	}
	mu.Lock()
	defer mu.Unlock()
	if current == nil || current.token == "" {
		return 0, fmt.Errorf("node is down")
	}
	if ln := current.loops[dest]; ln != nil {
		return ln.Addr().(*net.TCPAddr).Port, nil
	}
	ln, err := loopbackListener()
	if err != nil {
		return 0, err
	}
	current.loops[dest] = ln
	go acceptLoopback(ln, dest, current.token, current.server.Dial)
	return ln.Addr().(*net.TCPAddr).Port, nil
}

func acceptToLocal(ctx context.Context, ln net.Listener, sharePort int) {
	for {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		if !remoteIsTailnet(conn.RemoteAddr()) {
			_ = conn.Close()
			continue
		}
		go func(inbound net.Conn) {
			activeConns.Add(1)
			defer activeConns.Add(-1)
			upstream, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", sharePort), 5*time.Second)
			if err != nil {
				_ = inbound.Close()
				return
			}
			splice(inbound, upstream)
		}(conn)
		select {
		case <-ctx.Done():
			return
		default:
		}
	}
}

func acceptLoopback(ln net.Listener, dest, token string, dial func(context.Context, string, string) (net.Conn, error)) {
	for {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		go func(local net.Conn) {
			if !authorizeDial(local, token, dest) {
				return
			}
			activeConns.Add(1)
			defer activeConns.Add(-1)
			upstream, err := dial(context.Background(), "tcp", dest)
			if err != nil {
				_ = local.Close()
				return
			}
			splice(local, upstream)
		}(conn)
	}
}

func authorizeDial(conn net.Conn, token, dest string) bool {
	if !destinationIsTailnet(dest) {
		_ = conn.Close()
		return false
	}
	got, err := readDialToken(conn)
	if err != nil || !tokenMatches(got, token) {
		_ = conn.Close()
		return false
	}
	return true
}

func splice(a, b net.Conn) {
	defer a.Close()
	defer b.Close()
	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(a, b)
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(b, a)
		done <- struct{}{}
	}()
	<-done
}

func waitUntilUsable(ctx context.Context, server *tsnet.Server) (*ipnstate.Status, error) {
	waitCtx, cancel := context.WithTimeout(ctx, bringUpBudget)
	defer cancel()
	var loginSince time.Time
	var last *ipnstate.Status
	for {
		if err := waitCtx.Err(); err != nil {
			if last != nil && (statusKeyExpired(last) || needsLogin(last.BackendState)) {
				return last, nil
			}
			if err == context.DeadlineExceeded {
				return last, fmt.Errorf("timed out waiting for the tailnet")
			}
			return last, err
		}
		client, err := server.LocalClient()
		if err != nil {
			if !sleepWait(waitCtx, 200*time.Millisecond) {
				continue
			}
			continue
		}
		status, err := client.Status(waitCtx)
		if err != nil || status == nil {
			if !sleepWait(waitCtx, 200*time.Millisecond) {
				continue
			}
			continue
		}
		last = status
		rememberStatus(status)
		if statusKeyExpired(status) {
			return status, nil
		}
		switch status.BackendState {
		case "Running":
			if firstIPv4(status) != "" {
				return status, nil
			}
			loginSince = time.Time{}
		case "NeedsLogin", "NeedsMachineAuth":
			if loginURLPresent(status) {
				return status, nil
			}
			if loginSince.IsZero() {
				loginSince = time.Now()
			} else if time.Since(loginSince) >= loginStableFor {
				return status, nil
			}
		default:
			loginSince = time.Time{}
		}
		sleepWait(waitCtx, 250*time.Millisecond)
	}
}

func sleepWait(ctx context.Context, d time.Duration) bool {
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}

func serveWhenRunning(ctx context.Context, server *tsnet.Server, node *runningNode) {
	deadline := time.NewTimer(15 * time.Minute)
	defer deadline.Stop()
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for {
		if !refreshAndListen(ctx, server, node) {
			return
		}
		select {
		case <-ctx.Done():
			return
		case <-deadline.C:
			return
		case <-ticker.C:
		}
	}
}

func refreshAndListen(ctx context.Context, server *tsnet.Server, node *runningNode) bool {
	client, err := server.LocalClient()
	if err != nil {
		return currentNodeIs(node)
	}
	status, err := client.Status(ctx)
	if err != nil || status == nil {
		return currentNodeIs(node)
	}
	mu.Lock()
	if current != node {
		mu.Unlock()
		return false
	}
	rememberStatus(status)
	alreadyListening := node.ln != nil
	sharePort := node.sharePort
	running := status.BackendState == "Running" && firstIPv4(status) != ""
	mu.Unlock()
	if alreadyListening || !running {
		return true
	}
	ln, listenErr := server.Listen("tcp", fmt.Sprintf(":%d", sharePort))
	mu.Lock()
	if current != node {
		mu.Unlock()
		if ln != nil {
			_ = ln.Close()
		}
		return false
	}
	if listenErr != nil || ln == nil {
		mu.Unlock()
		return true
	}
	if node.ln != nil {
		mu.Unlock()
		_ = ln.Close()
		return true
	}
	node.ln = ln
	mu.Unlock()
	go acceptToLocal(ctx, ln, sharePort)
	return true
}

func currentNodeIs(node *runningNode) bool {
	mu.Lock()
	defer mu.Unlock()
	return current == node
}

func captureAuthURL(format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	if url := extractHTTPS(msg); url != "" {
		authURL.Store(url)
	}
}

func extractHTTPS(msg string) string {
	index := strings.Index(msg, "https://")
	if index < 0 {
		return ""
	}
	rest := msg[index:]
	if cut := strings.IndexAny(rest, " \t\r\n\"'"); cut >= 0 {
		rest = rest[:cut]
	}
	if !strings.Contains(rest, "tailscale") {
		return ""
	}
	return rest
}

func rememberStatus(status *ipnstate.Status) {
	if status == nil {
		return
	}
	report := reportFromStatus(status)
	report.ipv4 = firstIPv4(status)
	if status.AuthURL != "" {
		authURL.Store(status.AuthURL)
	}
	if report.backend == "Running" && report.ipv4 != "" {
		authURL.Store("")
	}
	lastReport = report
}

func reportFromStatus(status *ipnstate.Status) bringUpReport {
	if status == nil {
		return bringUpReport{}
	}
	return bringUpReport{
		backend:    status.BackendState,
		keyExpired: statusKeyExpired(status),
	}
}

func needsLogin(backend string) bool {
	return backend == "NeedsLogin" || backend == "NeedsMachineAuth"
}

func loginURLPresent(status *ipnstate.Status) bool {
	if status != nil && status.AuthURL != "" {
		return true
	}
	url, _ := authURL.Load().(string)
	return url != ""
}

func statusKeyExpired(status *ipnstate.Status) bool {
	if status == nil || status.Self == nil {
		return false
	}
	if status.Self.Expired {
		return true
	}
	expiry := status.Self.KeyExpiry
	if expiry == nil || expiry.IsZero() {
		return false
	}
	return time.Now().After(*expiry)
}

func firstIPv4(status *ipnstate.Status) string {
	if status == nil {
		return ""
	}
	if ip := firstAddrIPv4(status.TailscaleIPs); ip != "" {
		return ip
	}
	if len(status.TailscaleIPs) > 0 {
		return status.TailscaleIPs[0].String()
	}
	return ""
}

func firstAddrIPv4(addrs []netip.Addr) string {
	for _, addr := range addrs {
		if addr.Is4() {
			return addr.String()
		}
	}
	return ""
}

func newDialToken() (string, error) {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return hex.EncodeToString(buf), nil
}

func tokenMatches(got, want string) bool {
	if want == "" || len(got) != len(want) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(got), []byte(want)) == 1
}

func readDialToken(conn net.Conn) (string, error) {
	_ = conn.SetReadDeadline(time.Now().Add(dialTokenWait))
	buf := make([]byte, 0, dialTokenMaxLen)
	one := make([]byte, 1)
	for len(buf) <= dialTokenMaxLen {
		n, err := conn.Read(one)
		if n == 1 {
			if one[0] == '\n' {
				_ = conn.SetReadDeadline(time.Time{})
				return string(buf), nil
			}
			buf = append(buf, one[0])
			continue
		}
		if err != nil {
			return "", err
		}
	}
	return "", fmt.Errorf("dial preface rejected")
}

func loopbackListener() (net.Listener, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	if !listenerIsLoopback(ln) {
		_ = ln.Close()
		return nil, fmt.Errorf("loopback bind failed")
	}
	return ln, nil
}

func listenerIsLoopback(ln net.Listener) bool {
	tcp, ok := ln.Addr().(*net.TCPAddr)
	if !ok || tcp == nil || tcp.Port == 0 || tcp.IP == nil {
		return false
	}
	ip4 := tcp.IP.To4()
	return ip4 != nil && ip4.Equal(net.IP{127, 0, 0, 1})
}

func tailnetDestination(host string, port int) (string, bool) {
	if port <= 0 || port > 65535 {
		return "", false
	}
	addr, ok := parseTailnetAddr(host)
	if !ok {
		return "", false
	}
	return net.JoinHostPort(addr.String(), strconv.Itoa(port)), true
}

func destinationIsTailnet(dest string) bool {
	host, portText, err := net.SplitHostPort(dest)
	if err != nil {
		return false
	}
	port, err := strconv.Atoi(portText)
	if err != nil {
		return false
	}
	_, ok := tailnetDestination(host, port)
	return ok
}

func parseTailnetAddr(host string) (netip.Addr, bool) {
	host = strings.TrimSpace(host)
	host = strings.TrimPrefix(host, "[")
	host = strings.TrimSuffix(host, "]")
	addr, err := netip.ParseAddr(host)
	if err != nil {
		return netip.Addr{}, false
	}
	addr = addr.Unmap()
	if tailnetIPv4.Contains(addr) || tailnetIPv6.Contains(addr) {
		return addr, true
	}
	return netip.Addr{}, false
}

func remoteIsTailnet(addr net.Addr) bool {
	if addr == nil {
		return false
	}
	if tcp, ok := addr.(*net.TCPAddr); ok && tcp.IP != nil {
		parsed, ok := netip.AddrFromSlice(tcp.IP)
		return ok && (tailnetIPv4.Contains(parsed.Unmap()) || tailnetIPv6.Contains(parsed.Unmap()))
	}
	host, _, err := net.SplitHostPort(addr.String())
	if err != nil {
		return false
	}
	_, ok := parseTailnetAddr(host)
	return ok
}

func removeStateDir(dir string) error {
	if strings.TrimSpace(dir) == "" {
		return fmt.Errorf("missing state directory")
	}
	return removeDirContents(dir)
}

func redactLog(format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	if strings.Contains(msg, "tskey-") {
		return
	}
}

func safeClose(server *tsnet.Server) {
	if server == nil {
		return
	}
	defer func() { _ = recover() }()
	_ = server.Close()
}

func redact(msg string) string {
	if i := strings.Index(msg, "tskey-"); i >= 0 {
		return msg[:i] + "tskey-redacted"
	}
	return msg
}
