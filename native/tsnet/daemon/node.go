package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

const (
	partSuffix    = ".fileapex-part"
	completedKeep = 200
)

var supportedProtocols = []string{
	"fileapex.v1.identity",
	"fileapex.v1.files",
	"fileapex.v1.cluster",
	"fileapex.v1.pairing",
	"fileapex.v2.membership.chain",
}

// deviceRecord is the PairedDeviceEntity JSON the cluster already speaks.
type deviceRecord struct {
	DeviceID               string `json:"deviceId"`
	DeviceName             string `json:"deviceName"`
	LastKnownIP            string `json:"lastKnownIp"`
	Port                   int    `json:"port"`
	PublicKeyHash          string `json:"publicKeyHash"`
	PublicKey              string `json:"publicKey"`
	E2eeEnabled            bool   `json:"e2eeEnabled"`
	RootPath               string `json:"rootPath"`
	ClientVersion          string `json:"clientVersion"`
	ClientVersionCode      int    `json:"clientVersionCode"`
	Platform               string `json:"platform"`
	OS                     string `json:"os"`
	DeviceMake             string `json:"deviceMake"`
	DeviceModel            string `json:"deviceModel"`
	SupportedProtocolsJSON string `json:"supportedProtocolsJson"`
	TailnetHostname        string `json:"tailnetHostname"`
	TailnetIpv4            string `json:"tailnetIpv4"`
}

// nodeState is the PeerNodeState JSON returned by /api/v1/identity.
type nodeState struct {
	DeviceID           string   `json:"deviceId"`
	DeviceName         string   `json:"deviceName"`
	IPAddress          string   `json:"ipAddress"`
	LastKnownIP        string   `json:"lastKnownIp"`
	Port               int      `json:"port"`
	ClientVersion      string   `json:"clientVersion"`
	AppVersion         string   `json:"appVersion"`
	ClientVersionCode  int      `json:"clientVersionCode"`
	AppVersionCode     int      `json:"appVersionCode"`
	Platform           string   `json:"platform"`
	OS                 string   `json:"os"`
	DeviceMake         string   `json:"deviceMake"`
	DeviceModel        string   `json:"deviceModel"`
	SupportedProtocols []string `json:"supportedProtocols"`
	RootPath           string   `json:"rootPath"`
	PublicKeyHash      string   `json:"publicKeyHash"`
	PublicKey          string   `json:"publicKey"`
	PinRequired        bool     `json:"pinRequired"`
	IsRemoved          bool     `json:"isRemoved"`
	TLSPin             string   `json:"tlsPin,omitempty"`
	TLSPort            int      `json:"tlsPort,omitempty"`
}

type diskTx struct {
	TransactionID string `json:"transactionId"`
	SenderID      string `json:"senderId"`
	FinalPath     string `json:"finalPath"`
	Size          int64  `json:"size"`
}

type diskState struct {
	DeviceID   string         `json:"deviceId"`
	DeviceName string         `json:"deviceName"`
	Joined     bool           `json:"joined"`
	Peers      []deviceRecord `json:"peers"`
	Completed  []diskTx       `json:"completed"`
}

type identityFile struct {
	DeviceID   string `json:"deviceId"`
	DeviceName string `json:"deviceName"`
	// AutoName is true while the cluster name is still the generated Docker / Docker2 label.
	// A rename from another device, or FILEAPEX_NAME, clears it.
	AutoName bool `json:"autoName,omitempty"`
}

type fileConfig struct {
	AuthKey    string `json:"auth_key,omitempty"`
	HostName   string `json:"hostname"`
	Mode       string `json:"mode"`
	Joined     bool   `json:"joined"`
	DeviceID   string `json:"device_id,omitempty"`
	PairingKey string `json:"pairing_key,omitempty"`
	// Tailscale is set only after a local join: "browser" or "auth_key".
	Tailscale string `json:"tailscale,omitempty"`
}

// Node is a LAN cluster member that joins by listening for a pairing beacon
// and then exchanges the same handshake the Android and desktop apps use.
type Node struct {
	mu              sync.Mutex
	dataDir         string
	inbox           string
	port            int
	identity        identityFile
	joined          bool
	tailscaleMode   string
	tailscaleKey    string
	tailscaleUp     bool
	peers           map[string]deviceRecord
	completed       []diskTx
	pins            map[string]string
	sessionCh       chan struct{}
	lastDialIP      string
	forceAdvertise  string
	allowLoopback   bool
	dialTimeout     time.Duration
	announceTimeout time.Duration
	clusterPath     string
	identityPath    string
	configPath      string
	httpClient      *http.Client
	httpServer      *http.Server
	helloConn       *net.UDPConn
	tls             *tlsState
	uploadMu        sync.Mutex
	signing         *ecdsa.PrivateKey
	chain           *chainLog
	chainPath       string
	applier         *chainApplier
	handledRemoval  string
	pairedAt        time.Time
	tickMu          sync.Mutex
}

func newNode(dataDir string) (*Node, error) {
	abs, err := filepath.Abs(dataDir)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(abs, 0o755); err != nil {
		return nil, err
	}
	inbox := filepath.Join(abs, "inbox")
	if err := os.MkdirAll(inbox, 0o755); err != nil {
		return nil, err
	}
	n := &Node{
		dataDir:      abs,
		inbox:        inbox,
		port:         8080,
		peers:        map[string]deviceRecord{},
		pins:         map[string]string{},
		sessionCh:    make(chan struct{}),
		clusterPath:  filepath.Join(abs, "cluster.json"),
		identityPath: filepath.Join(abs, "identity.json"),
		configPath:   filepath.Join(abs, "config.json"),
		chainPath:    filepath.Join(abs, "chain.json"),
	}
	n.httpClient = &http.Client{Transport: &http.Transport{
		Proxy:                 nil,
		DialContext:           n.dial,
		ResponseHeaderTimeout: 20 * time.Second,
		IdleConnTimeout:       30 * time.Second,
	}}
	if err := n.loadIdentity(); err != nil {
		return nil, err
	}
	if state, err := newTLSState(abs, time.Now()); err != nil {
		log.Printf("TLS disabled: %v", err)
	} else {
		n.tls = state
	}
	if err := n.loadCluster(); err != nil {
		log.Printf("Ignoring unreadable cluster state: %v", err)
	}
	if n.signing, err = loadOrCreateSigningKey(abs); err != nil {
		return nil, fmt.Errorf("membership signing key: %w", err)
	}
	n.chain = newChainLog(n.deviceID)
	n.applier = newChainApplier(n.identity.DeviceID, chainEffectsOf{n}, time.Now)
	n.loadChain()
	return n, nil
}

func (n *Node) loadIdentity() error {
	body, err := os.ReadFile(n.identityPath)
	if errors.Is(err, os.ErrNotExist) {
		n.identity = identityFile{DeviceID: newUUID(), DeviceName: "Docker", AutoName: true}
		return writeJSONFile(n.identityPath, n.identity)
	}
	if err != nil {
		return err
	}
	if err := json.Unmarshal(body, &n.identity); err != nil {
		return fmt.Errorf("identity file: %w", err)
	}
	n.identity.DeviceID = strings.TrimSpace(n.identity.DeviceID)
	n.identity.DeviceName = strings.TrimSpace(n.identity.DeviceName)
	if n.identity.DeviceID == "" || n.identity.DeviceName == "" {
		return fmt.Errorf("identity file is missing a device id or name")
	}
	if n.identity.AutoName || legacyAutoName(n.identity.DeviceName) {
		n.identity.AutoName = true
		if legacyAutoName(n.identity.DeviceName) {
			n.identity.DeviceName = "Docker"
		}
		if err := writeJSONFile(n.identityPath, n.identity); err != nil {
			return err
		}
	}
	return nil
}

func legacyAutoName(name string) bool {
	name = strings.TrimSpace(name)
	if strings.EqualFold(name, "fileapex-omv") {
		return true
	}
	host, err := os.Hostname()
	if err != nil {
		return false
	}
	return name != "" && strings.EqualFold(name, strings.TrimSpace(host))
}

func (n *Node) loadCluster() error {
	body, err := os.ReadFile(n.clusterPath)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	var disk diskState
	if err := json.Unmarshal(body, &disk); err != nil {
		return err
	}
	if disk.DeviceID != "" && disk.DeviceID != n.identity.DeviceID {
		return fmt.Errorf("cluster file belongs to %s, this node is %s", disk.DeviceID, n.identity.DeviceID)
	}
	n.joined = disk.Joined
	for _, peer := range disk.Peers {
		if peer.DeviceID == "" || peer.DeviceID == n.identity.DeviceID {
			continue
		}
		n.peers[peer.DeviceID] = peer
	}
	n.completed = disk.Completed
	return nil
}

func (n *Node) persistLocked() {
	peers := make([]deviceRecord, 0, len(n.peers))
	for _, peer := range n.peers {
		peers = append(peers, peer)
	}
	disk := diskState{
		DeviceID:   n.identity.DeviceID,
		DeviceName: n.identity.DeviceName,
		Joined:     n.joined,
		Peers:      peers,
		Completed:  n.completed,
	}
	if err := writeJSONFile(n.clusterPath, disk); err != nil {
		log.Printf("Could not save cluster state: %v", err)
	}
	if err := writeJSONFile(n.identityPath, n.identity); err != nil {
		log.Printf("Could not save identity: %v", err)
	}
	cfg := fileConfig{
		HostName:  n.identity.DeviceName,
		Mode:      "local",
		Joined:    n.joined,
		DeviceID:  n.identity.DeviceID,
		AuthKey:   n.tailscaleKey,
		Tailscale: n.tailscaleMode,
	}
	if err := writeJSONFile(n.configPath, cfg); err != nil {
		log.Printf("Could not save config: %v", err)
	}
}

func (n *Node) setName(name string) {
	n.assignName(name, false)
}

func (n *Node) setAutoName(name string) {
	n.assignName(name, true)
}

func (n *Node) assignName(name string, auto bool) {
	name = strings.TrimSpace(name)
	if name == "" {
		return
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	n.identity.DeviceName = name
	n.identity.AutoName = auto
	n.persistLocked()
}

func (n *Node) setInbox(path string) error {
	abs, err := filepath.Abs(path)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(abs, 0o755); err != nil {
		return err
	}
	n.mu.Lock()
	n.inbox = abs
	n.mu.Unlock()
	return nil
}

func (n *Node) deviceID() string {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.identity.DeviceID
}

func (n *Node) deviceName() string {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.identity.DeviceName
}

func (n *Node) isJoined() bool {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.joined
}

func (n *Node) usesAutoName() bool {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.identity.AutoName
}

func (n *Node) noteTailscale(mode, key string) {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.tailscaleMode = strings.TrimSpace(mode)
	n.tailscaleKey = strings.TrimSpace(key)
}

func (n *Node) saveTailscale(mode, key string) {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.tailscaleMode = strings.TrimSpace(mode)
	n.tailscaleKey = strings.TrimSpace(key)
	n.persistLocked()
}

func (n *Node) tailscaleSettings() (mode, key string) {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.tailscaleMode, n.tailscaleKey
}

func (n *Node) claimTailscaleStart() (mode, key string, port int, ok bool) {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.tailscaleUp || !n.joined || n.tailscaleMode == "" {
		return "", "", 0, false
	}
	if n.tailscaleMode == "auth_key" && n.tailscaleKey == "" {
		return "", "", 0, false
	}
	if n.port <= 0 {
		return "", "", 0, false
	}
	n.tailscaleUp = true
	return n.tailscaleMode, n.tailscaleKey, n.port, true
}

// resetCluster is the user's reset: the other members are told with a LEAVE, then everything of the cluster and the
// Tailscale setup is let go. The device id stays, so a later pairing is the same node.
func (n *Node) resetCluster(ctx context.Context) {
	n.leaveCluster(ctx)
	n.mu.Lock()
	n.tailscaleMode = ""
	n.tailscaleKey = ""
	n.persistLocked()
	n.mu.Unlock()
}

func (n *Node) listenPort() int {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.port
}

func (n *Node) inboxDir() string {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.inbox
}

func (n *Node) setDialIP(ip string) {
	parsed := net.ParseIP(ip)
	if parsed == nil || (!isPrivateLAN(ip) && !parsed.IsLoopback()) {
		return
	}
	n.mu.Lock()
	n.lastDialIP = ip
	n.mu.Unlock()
}

func (n *Node) advertiseIP() string {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.advertiseIPLocked()
}

func (n *Node) advertiseIPLocked() string {
	if n.forceAdvertise != "" {
		return n.forceAdvertise
	}
	if isUsableAdvertise(n.lastDialIP) {
		return n.lastDialIP
	}
	return bestLocalIP()
}

func (n *Node) dial(ctx context.Context, network, addr string) (net.Conn, error) {
	timeout := n.dialTimeout
	if timeout <= 0 {
		timeout = 5 * time.Second
	}
	conn, err := (&net.Dialer{Timeout: timeout}).DialContext(ctx, network, addr)
	if err != nil {
		return nil, err
	}
	if ta, ok := conn.LocalAddr().(*net.TCPAddr); ok {
		if ip4 := ta.IP.To4(); ip4 != nil {
			n.setDialIP(ip4.String())
		}
	}
	return conn, nil
}

func (n *Node) shouldDial(ip string) bool {
	if isPrivateLAN(ip) {
		return true
	}
	parsed := net.ParseIP(strings.TrimSpace(ip))
	return n.allowLoopback && parsed != nil && parsed.IsLoopback()
}

// handshake is the joiner half of pairing: read the host's identity, pair on protocol 2 over its TLS port, take the
// host's cluster (the host wins: this node's own ends) and ask an active member to admit it.
func (n *Node) handshake(ctx context.Context, beacon pairingBeacon, pin string) error {
	remote, err := n.fetchIdentity(ctx, beacon.IPAddress, beacon.Port)
	if err != nil {
		log.Printf("Could not read identity from %s (%v). Continuing with the broadcast.", beacon.DeviceName, err)
	}
	hostID := beacon.DeviceID
	if strings.TrimSpace(remote.DeviceID) != "" {
		hostID = strings.TrimSpace(remote.DeviceID)
	}
	ip := n.advertiseIP()
	if n.forceAdvertise != "" {
		ip = n.forceAdvertise
		if name := ifaceName(ip); isContainerBridge(name) {
			log.Printf("Warning: advertised address %s is on %s. Other devices may not be able to reach this container.", ip, name)
		}
	}
	parsed := net.ParseIP(ip)
	if parsed == nil || (parsed.IsLoopback() && !n.allowLoopback) || parsed.IsUnspecified() || (n.forceAdvertise == "" && !isUsableAdvertise(ip)) {
		return fmt.Errorf("no LAN address other devices can reach (saw %q)", ip)
	}

	host := deviceFromState(remote)
	host.DeviceID = hostID
	if host.DeviceName == "" {
		host.DeviceName = beacon.DeviceName
	}
	// The address we just reached is the one peers can use. The identity payload can name a different NIC that this container cannot route to.
	host.LastKnownIP = beacon.IPAddress
	host.Port = beacon.Port
	if host.RootPath == "" {
		host.RootPath = "/"
	}
	n.upsertPeer(host)

	hostPin := strings.ToLower(strings.TrimSpace(remote.TLSPin))
	if hostPin == "" {
		hostPin = strings.ToLower(strings.TrimSpace(beacon.TLSPin))
	}
	tlsPort := remote.TLSPort
	if tlsPort == 0 {
		tlsPort = beacon.TLSPort
	}
	provenPin, err := n.securePair(ctx, beacon, hostID, hostPin, tlsPort, pin, n.selfRecord(ip))
	if err != nil {
		n.removePeer(hostID)
		return err
	}
	// The host proved this key through the number its user compared; every later request to it is pinned.
	n.tls.record(hostID, provenPin, tlsPort)
	log.Printf("Pairing handshake accepted by %s.", host.DeviceName)
	n.rememberPin(hostID, pin)
	n.markJoined()
	if !n.joinClusterOf(ctx, hostID) {
		log.Printf("Could not read the cluster of %s yet; the next tick will try again.", host.DeviceName)
	}
	n.tickChain(ctx)
	n.disambiguateAutoNameFromRoster()
	n.announceChain(ctx)
	n.logRoster()
	return nil
}

func (n *Node) fetchIdentity(ctx context.Context, host string, port int) (nodeState, error) {
	body, status, err := n.get(ctx, peerURL(host, port, "/api/v1/identity", nil), 8*time.Second)
	if err != nil {
		return nodeState{}, err
	}
	if status < 200 || status > 299 {
		return nodeState{}, fmt.Errorf("HTTP %d", status)
	}
	var state nodeState
	if err := json.Unmarshal(body, &state); err != nil {
		return nodeState{}, err
	}
	return state, nil
}

func (n *Node) disambiguateAutoNameFromRoster() {
	if !n.usesAutoName() {
		return
	}
	taken := map[string]struct{}{}
	for _, peer := range n.roster() {
		key := strings.ToLower(strings.TrimSpace(peer.DeviceName))
		if key != "" {
			taken[key] = struct{}{}
		}
	}
	name := pickDockerName(taken)
	if strings.EqualFold(name, n.deviceName()) {
		return
	}
	n.setAutoName(name)
	log.Printf("Cluster name is %s", name)
}

func pickDockerName(taken map[string]struct{}) string {
	if _, ok := taken["docker"]; !ok {
		return "Docker"
	}
	for i := 2; i < 1000; i++ {
		name := fmt.Sprintf("Docker%d", i)
		if _, ok := taken[strings.ToLower(name)]; !ok {
			return name
		}
	}
	return "Docker"
}

func (n *Node) get(ctx context.Context, rawURL string, timeout time.Duration) ([]byte, int, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return nil, 0, err
	}
	return n.do(req, timeout)
}

// do reaches a pinned peer over TLS and everyone else over HTTP.
func (n *Node) do(req *http.Request, timeout time.Duration) ([]byte, int, error) {
	client, out := n.clientFor(req)
	return n.send(client, out, timeout)
}

// doPlain is for the pin exchange, which a peer that does not know our pin yet must be able to receive.
func (n *Node) doPlain(req *http.Request, timeout time.Duration) ([]byte, int, error) {
	return n.send(n.httpClient, req, timeout)
}

func (n *Node) send(client *http.Client, req *http.Request, timeout time.Duration) ([]byte, int, error) {
	body, status, _, err := n.sendHeader(client, req, timeout)
	return body, status, err
}

func (n *Node) sendHeader(client *http.Client, req *http.Request, timeout time.Duration) ([]byte, int, http.Header, error) {
	ctx, cancel := context.WithTimeout(req.Context(), timeout)
	defer cancel()
	resp, err := client.Do(req.WithContext(ctx))
	if err != nil {
		return nil, 0, nil, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if err != nil {
		return nil, resp.StatusCode, resp.Header, err
	}
	return body, resp.StatusCode, resp.Header, nil
}

func (n *Node) rememberPin(deviceID, pin string) {
	deviceID = strings.TrimSpace(deviceID)
	pin = strings.TrimSpace(pin)
	if deviceID == "" || pin == "" {
		return
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	n.pins[deviceID] = pin
}

func (n *Node) pinFor(deviceID string) string {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.pins[strings.TrimSpace(deviceID)]
}

func (n *Node) upsertPeer(rec deviceRecord) {
	rec.DeviceID = strings.TrimSpace(rec.DeviceID)
	if rec.DeviceID == "" {
		return
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	if rec.DeviceID == n.identity.DeviceID {
		return
	}
	n.peers[rec.DeviceID] = rec
	n.persistLocked()
}

func (n *Node) removePeer(id string) {
	n.mu.Lock()
	defer n.mu.Unlock()
	delete(n.peers, id)
	if n.tls != nil {
		n.tls.forget(id)
	}
	n.persistLocked()
}

func (n *Node) markJoined() {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.joined = true
	n.persistLocked()
}

func (n *Node) waitWhileJoined(ctx context.Context) {
	n.mu.Lock()
	ch := n.sessionCh
	joined := n.joined
	n.mu.Unlock()
	if !joined {
		return
	}
	select {
	case <-ctx.Done():
	case <-ch:
	}
}

func (n *Node) allowUpload(id string) bool {
	id = strings.TrimSpace(id)
	if id == "" || id == n.deviceID() {
		return false
	}
	if n.tls != nil && n.tls.isRevoked(id) {
		return false
	}
	return n.onRoster(id)
}

func (n *Node) noteContact(id, remote string) {
	id = strings.TrimSpace(id)
	ip := remoteIPv4(remote)
	if id == "" || !isPrivateLAN(ip) {
		return
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	peer, ok := n.peers[id]
	if !ok || peer.LastKnownIP == ip {
		return
	}
	peer.LastKnownIP = ip
	n.peers[id] = peer
	n.persistLocked()
}

func (n *Node) roster() []deviceRecord {
	n.mu.Lock()
	defer n.mu.Unlock()
	out := make([]deviceRecord, 0, len(n.peers))
	for _, peer := range n.peers {
		if peer.DeviceID == "" || peer.DeviceID == n.identity.DeviceID {
			continue
		}
		out = append(out, peer)
	}
	sort.Slice(out, func(i, j int) bool {
		return strings.ToLower(out[i].DeviceName) < strings.ToLower(out[j].DeviceName)
	})
	return out
}

func (n *Node) logRoster() {
	peers := n.roster()
	log.Printf("Joined the cluster as %s (%s) at %s:%d", n.deviceName(), n.deviceID(), n.advertiseIP(), n.listenPort())
	log.Printf("Incoming files are accepted only from paired peers.")
	log.Printf("Backup folder: %s", n.inboxDir())
	if len(peers) == 0 {
		log.Printf("No peers stored yet.")
		return
	}
	log.Printf("Peers (%d):", len(peers))
	for _, peer := range peers {
		log.Printf("  - %s (%s) %s:%d", peer.DeviceName, peer.DeviceID, peer.LastKnownIP, peer.Port)
	}
}

func (n *Node) selfState() nodeState {
	n.mu.Lock()
	defer n.mu.Unlock()
	return nodeState{
		DeviceID:           n.identity.DeviceID,
		DeviceName:         n.identity.DeviceName,
		IPAddress:          n.advertiseIPLocked(),
		Port:               n.port,
		ClientVersion:      "docker",
		AppVersion:         strings.TrimSpace(appVersionName),
		Platform:           "linux",
		OS:                 "linux",
		DeviceMake:         "FileApex",
		DeviceModel:        "OMV",
		SupportedProtocols: supportedProtocols,
		RootPath:           n.inbox,
		PublicKeyHash:      fingerprint(n.identity.DeviceID),
		PublicKey:          n.pairPublicKey(),
		PinRequired:        false,
		TLSPin:             n.tlsPinValue(),
		TLSPort:            n.tlsPort(),
	}
}

func (n *Node) selfRecord(ip string) deviceRecord {
	n.mu.Lock()
	defer n.mu.Unlock()
	protocols, _ := json.Marshal(supportedProtocols)
	return deviceRecord{
		DeviceID:               n.identity.DeviceID,
		DeviceName:             n.identity.DeviceName,
		LastKnownIP:            ip,
		Port:                   n.port,
		PublicKeyHash:          fingerprint(n.identity.DeviceID),
		PublicKey:              n.pairPublicKey(),
		RootPath:               n.inbox,
		ClientVersion:          "docker",
		Platform:               "linux",
		OS:                     "linux",
		DeviceMake:             "FileApex",
		DeviceModel:            "OMV",
		SupportedProtocolsJSON: string(protocols),
	}
}

func (n *Node) findCompleted(tx, sender string, size int64) (diskTx, bool) {
	if strings.TrimSpace(tx) == "" {
		return diskTx{}, false
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	for i := len(n.completed) - 1; i >= 0; i-- {
		entry := n.completed[i]
		if entry.TransactionID != tx {
			continue
		}
		if sender != "" && entry.SenderID != "" && !strings.EqualFold(entry.SenderID, sender) {
			continue
		}
		if size > 0 && entry.Size != size {
			continue
		}
		if _, err := os.Stat(entry.FinalPath); err != nil {
			continue
		}
		return entry, true
	}
	return diskTx{}, false
}

func (n *Node) rememberCompleted(entry diskTx) {
	if entry.TransactionID == "" {
		return
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	n.completed = append(n.completed, entry)
	if len(n.completed) > completedKeep {
		n.completed = n.completed[len(n.completed)-completedKeep:]
	}
	n.persistLocked()
}

func deviceFromState(state nodeState) deviceRecord {
	ip := strings.TrimSpace(state.IPAddress)
	if ip == "" {
		ip = strings.TrimSpace(state.LastKnownIP)
	}
	root := strings.TrimSpace(state.RootPath)
	if root == "" {
		root = "/"
	}
	protocols := state.SupportedProtocols
	if len(protocols) == 0 {
		protocols = supportedProtocols
	}
	encoded, _ := json.Marshal(protocols)
	versionCode := state.ClientVersionCode
	if versionCode <= 0 {
		versionCode = state.AppVersionCode
	}
	return deviceRecord{
		DeviceID:               strings.TrimSpace(state.DeviceID),
		DeviceName:             strings.TrimSpace(state.DeviceName),
		LastKnownIP:            ip,
		Port:                   state.Port,
		PublicKeyHash:          strings.TrimSpace(state.PublicKeyHash),
		PublicKey:              state.PublicKey,
		E2eeEnabled:            strings.TrimSpace(state.PublicKey) != "",
		RootPath:               root,
		ClientVersion:          firstNonEmpty(state.ClientVersion, state.AppVersion),
		ClientVersionCode:      versionCode,
		Platform:               strings.TrimSpace(state.Platform),
		OS:                     strings.TrimSpace(state.OS),
		DeviceMake:             strings.TrimSpace(state.DeviceMake),
		DeviceModel:            strings.TrimSpace(state.DeviceModel),
		SupportedProtocolsJSON: string(encoded),
	}
}

func (n *Node) renameSelf(name string) {
	n.setName(name)
}

func fingerprint(deviceID string) string {
	var hash uint64 = 0xcbf29ce484222325
	const prime uint64 = 0x100000001b3
	for _, ch := range deviceID {
		hash ^= uint64(ch)
		hash *= prime
	}
	return fmt.Sprintf("%016x", hash)
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return strings.TrimSpace(value)
		}
	}
	return ""
}

func strconvFormat(v int64) string {
	return fmt.Sprintf("%d", v)
}

func newUUID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(err)
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:])
}

func writeJSONFile(path string, value any) error {
	body, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	body = append(body, '\n')
	tmp := path + ".tmp"
	// The roster, peer pins and removal records are private to this node.
	if err := os.WriteFile(tmp, body, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func peerURL(host string, port int, path string, query url.Values) string {
	return (&url.URL{
		Scheme:   "http",
		Host:     net.JoinHostPort(host, fmt.Sprintf("%d", port)),
		Path:     path,
		RawQuery: query.Encode(),
	}).String()
}

func (n *Node) pairPublicKey() string {
	if n.tls == nil {
		return ""
	}
	return n.tls.publicKeyB64()
}

func (n *Node) tlsPinValue() string {
	if n.tls == nil {
		return ""
	}
	return n.tls.current().pin
}
