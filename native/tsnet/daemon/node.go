package main

import (
	"bytes"
	"context"
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
	membershipProtocol = 2
	maxForwardSkew     = 10 * time.Minute
	partSuffix         = ".fileapex-part"
	completedKeep      = 200
)

var supportedProtocols = []string{
	"fileapex.v1.identity",
	"fileapex.v1.files",
	"fileapex.v1.cluster",
	"fileapex.v1.pairing",
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
	LastSeenEpochMs        int64  `json:"lastSeenEpochMs,omitempty"`
	ClusterVersion         int64  `json:"clusterVersion"`
	IsRemoved              bool   `json:"isRemoved"`
	RemovedAt              *int64 `json:"removedAt"`
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
	LastSeenTimestamp  int64    `json:"lastSeenTimestamp,omitempty"`
	RootPath           string   `json:"rootPath"`
	PublicKeyHash      string   `json:"publicKeyHash"`
	PublicKey          string   `json:"publicKey"`
	PinRequired        bool     `json:"pinRequired"`
	DownloadsPath      string   `json:"downloadsPath"`
	ClusterVersion     int64    `json:"clusterVersion"`
	IsRemoved          bool     `json:"isRemoved"`
	RemovedAt          *int64   `json:"removedAt"`
	MembershipVersion  int64    `json:"membershipVersion"`
	MembershipProtocol int      `json:"membershipProtocol"`
	TLSPin             string   `json:"tlsPin,omitempty"`
	TLSPort            int      `json:"tlsPort,omitempty"`
}

type removedRecord struct {
	DeviceID           string `json:"deviceId"`
	PublicKeyHash      string `json:"publicKeyHash"`
	LastKnownIP        string `json:"lastKnownIp"`
	Port               int    `json:"port"`
	ClusterVersion     int64  `json:"clusterVersion"`
	RemovedAt          *int64 `json:"removedAt"`
	MembershipProtocol int    `json:"membershipProtocol"`
}

func (r removedRecord) version() int64 {
	v := r.ClusterVersion
	if r.RemovedAt != nil && *r.RemovedAt > v {
		v = *r.RemovedAt
	}
	return v
}

type clusterSync struct {
	EventKind      string          `json:"eventKind"`
	NodeStates     []nodeState     `json:"nodeStates"`
	RemovedDevices []removedRecord `json:"removedDevices"`
	ClusterVersion int64           `json:"clusterVersion"`
	Introducer     *deviceRecord   `json:"introducer"`
	Devices        []deviceRecord  `json:"devices"`
}

type diskTomb struct {
	DeviceID      string `json:"deviceId"`
	PublicKeyHash string `json:"publicKeyHash"`
	Version       int64  `json:"version"`
}

type diskTx struct {
	TransactionID string `json:"transactionId"`
	SenderID      string `json:"senderId"`
	FinalPath     string `json:"finalPath"`
	Size          int64  `json:"size"`
}

type diskState struct {
	DeviceID          string         `json:"deviceId"`
	DeviceName        string         `json:"deviceName"`
	MembershipVersion int64          `json:"membershipVersion"`
	Joined            bool           `json:"joined"`
	Removed           bool           `json:"removed"`
	RemovedVersion    int64          `json:"removedVersion"`
	Peers             []deviceRecord `json:"peers"`
	Tombstones        []diskTomb     `json:"tombstones"`
	Completed         []diskTx       `json:"completed"`
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
	membership      int64
	joined          bool
	removed         bool
	removedVersion  int64
	tailscaleMode   string
	tailscaleKey    string
	tailscaleUp     bool
	peers           map[string]deviceRecord
	tombstones      map[string]diskTomb
	completed       []diskTx
	pending         map[string]struct{}
	pins            map[string]string
	announceLogged  map[string]time.Time
	sessionCh       chan struct{}
	seedNotify      chan struct{}
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
	tls             *tlsState
	uploadMu        sync.Mutex
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
		dataDir:        abs,
		inbox:          inbox,
		port:           8080,
		peers:          map[string]deviceRecord{},
		tombstones:     map[string]diskTomb{},
		pending:        map[string]struct{}{},
		pins:           map[string]string{},
		announceLogged: map[string]time.Time{},
		sessionCh:      make(chan struct{}),
		seedNotify:     make(chan struct{}, 1),
		clusterPath:    filepath.Join(abs, "cluster.json"),
		identityPath:   filepath.Join(abs, "identity.json"),
		configPath:     filepath.Join(abs, "config.json"),
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
	n.membership = disk.MembershipVersion
	n.joined = disk.Joined && !disk.Removed
	n.removed = disk.Removed
	n.removedVersion = disk.RemovedVersion
	for _, peer := range disk.Peers {
		if peer.DeviceID == "" || peer.DeviceID == n.identity.DeviceID || peer.IsRemoved {
			continue
		}
		peer.LastSeenEpochMs = 0
		n.peers[peer.DeviceID] = peer
	}
	for _, tomb := range disk.Tombstones {
		if tomb.DeviceID != "" && tomb.Version > 0 {
			n.tombstones[tomb.DeviceID] = tomb
		}
	}
	n.completed = disk.Completed
	return nil
}

func (n *Node) persistLocked() {
	peers := make([]deviceRecord, 0, len(n.peers))
	for _, peer := range n.peers {
		if !peer.IsRemoved {
			peer.LastSeenEpochMs = 0
			peers = append(peers, peer)
		}
	}
	tombs := make([]diskTomb, 0, len(n.tombstones))
	for _, tomb := range n.tombstones {
		tombs = append(tombs, tomb)
	}
	disk := diskState{
		DeviceID:          n.identity.DeviceID,
		DeviceName:        n.identity.DeviceName,
		MembershipVersion: n.membership,
		Joined:            n.joined,
		Removed:           n.removed,
		RemovedVersion:    n.removedVersion,
		Peers:             peers,
		Tombstones:        tombs,
		Completed:         n.completed,
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
		Joined:    n.joined && !n.removed,
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
	return n.joined && !n.removed
}

func (n *Node) wasRemoved() bool {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.removed
}

func (n *Node) removalVersion() int64 {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.removedVersion
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
	if n.tailscaleUp || !n.joined || n.removed || n.tailscaleMode == "" {
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

func (n *Node) resetCluster() {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.joined = false
	n.removed = false
	n.removedVersion = 0
	n.membership = 0
	n.tailscaleMode = ""
	n.tailscaleKey = ""
	n.peers = map[string]deviceRecord{}
	n.tombstones = map[string]diskTomb{}
	n.pins = map[string]string{}
	if n.tls != nil {
		n.tls.forgetAll()
	}
	n.completed = nil
	previous := n.sessionCh
	n.sessionCh = make(chan struct{})
	n.persistLocked()
	select {
	case <-previous:
	default:
		close(previous)
	}
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

func (n *Node) identityStamp() (string, int64) {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.identity.DeviceID, n.membership
}

func (n *Node) hasMembership() bool {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.membership > 0
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

// handshake is the scanner half of pairing: read the host's identity, POST
// /api/v1/pairing/respond, then accept the roster seed the host pushes back.
func (n *Node) handshake(ctx context.Context, beacon pairingBeacon, pin string) error {
	if n.wasRemoved() {
		return fmt.Errorf("removed from the cluster at version %d; start with --reset to pair again", n.removalVersion())
	}
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
	if parsed == nil || parsed.IsLoopback() || parsed.IsUnspecified() || (n.forceAdvertise == "" && !isUsableAdvertise(ip)) {
		return fmt.Errorf("no LAN address other devices can reach (saw %q)", ip)
	}

	host := deviceFromState(remote, 0)
	host.DeviceID = hostID
	if host.DeviceName == "" {
		host.DeviceName = beacon.DeviceName
	}
	// The address we just reached is the one peers can use. The identity
	// payload can name a different NIC that this container cannot route to.
	host.LastKnownIP = beacon.IPAddress
	host.Port = beacon.Port
	if host.RootPath == "" {
		host.RootPath = "/"
	}
	now := time.Now().UnixMilli()
	version := remote.MembershipVersion
	if remote.MembershipProtocol < membershipProtocol || !versionAcceptable(version, now) {
		version = remote.ClusterVersion
	}
	if !versionAcceptable(version, now) {
		version = now
	}
	host.ClusterVersion = version
	host.IsRemoved = false
	host.RemovedAt = nil
	n.upsertPeer(host)

	n.setPending(hostID, beacon.DeviceID)
	defer n.setPending()
	n.drainSeed()
	n.chooseAutoName(ctx, beacon)

	status, respBody, err := n.postPairing(ctx, beacon, pin, n.selfRecord(ip))
	if err != nil {
		n.removePeer(hostID)
		return err
	}
	if status == http.StatusForbidden {
		n.removePeer(hostID)
		text := strings.ToLower(string(respBody))
		if strings.Contains(text, "pairing_code") {
			return fmt.Errorf("pairing code expired before the handshake finished")
		}
		if strings.Contains(text, "pin") {
			return fmt.Errorf("the other device rejected the PIN")
		}
		return fmt.Errorf("pairing refused (%s)", strings.TrimSpace(string(respBody)))
	}
	if status < 200 || status > 299 {
		n.removePeer(hostID)
		return fmt.Errorf("pairing handshake returned HTTP %d (%s)", status, strings.TrimSpace(string(respBody)))
	}
	log.Printf("Pairing handshake accepted by %s.", host.DeviceName)
	// Exchange TLS pins before the host starts pushing the roster, so both sides can switch to TLS.
	if err := n.announceTLSTo(ctx, host, time.Now()); err != nil && !errors.Is(err, errTLSUnsupported) {
		log.Printf("TLS pin exchange with %s did not complete (%v); it will retry.", host.DeviceName, err)
	}
	n.rememberPin(hostID, pin)
	n.markJoined()
	if err := n.finishRoster(ctx, host); err != nil && ctx.Err() != nil {
		return err
	}
	n.logRoster()
	return nil
}

func (n *Node) finishRoster(ctx context.Context, host deviceRecord) error {
	select {
	case <-n.seedNotify:
		log.Printf("Received the cluster roster from %s.", host.DeviceName)
	case <-time.After(8 * time.Second):
		log.Printf("No roster push from %s within 8s; reading its device list.", host.DeviceName)
	case <-ctx.Done():
		return ctx.Err()
	}
	if err := n.importDeviceList(ctx, host.LastKnownIP, host.Port); err != nil {
		log.Printf("Device list import from %s failed: %v", host.DeviceName, err)
	}
	n.disambiguateAutoNameFromRoster()
	if !n.hasMembership() {
		log.Printf("Pairing host did not stamp a membership version; using the local clock.")
		n.ensureMembership()
	}
	n.announceSelf(ctx, "PAIRING_INTRO")
	return nil
}

func (n *Node) fetchIdentity(ctx context.Context, host string, port int) (nodeState, error) {
	q := url.Values{}
	n.stampQuery(q)
	body, status, err := n.get(ctx, peerURL(host, port, "/api/v1/identity", q), 8*time.Second)
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

func (n *Node) postPairing(ctx context.Context, beacon pairingBeacon, pin string, self deviceRecord) (int, []byte, error) {
	q := url.Values{}
	n.stampQuery(q)
	q.Set("code", beacon.PairingCode)
	if strings.TrimSpace(pin) != "" {
		q.Set("pin", strings.TrimSpace(pin))
	}
	payload, err := json.Marshal(self)
	if err != nil {
		return 0, nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, peerURL(beacon.IPAddress, beacon.Port, "/api/v1/pairing/respond", q), bytes.NewReader(payload))
	if err != nil {
		return 0, nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-FileApex-Device-Id", self.DeviceID)
	req.Header.Set("X-FileApex-Pairing-Code", beacon.PairingCode)
	if strings.TrimSpace(pin) != "" {
		req.Header.Set("X-FileApex-Pin", strings.TrimSpace(pin))
	}
	body, status, err := n.do(req, 20*time.Second)
	return status, body, err
}

func (n *Node) importDeviceList(ctx context.Context, host string, port int) error {
	roster, err := n.fetchDevices(ctx, host, port)
	if err != nil {
		return err
	}
	n.adoptTrusted(roster)
	return nil
}

func (n *Node) fetchDevices(ctx context.Context, host string, port int) ([]deviceRecord, error) {
	if !n.shouldDial(host) {
		return nil, fmt.Errorf("host %s is not a LAN address", host)
	}
	q := url.Values{}
	n.stampQuery(q)
	body, status, err := n.get(ctx, peerURL(host, port, "/api/v1/devices", q), 8*time.Second)
	if err != nil {
		return nil, err
	}
	if status < 200 || status > 299 {
		return nil, fmt.Errorf("HTTP %d", status)
	}
	var roster []deviceRecord
	if err := json.Unmarshal(body, &roster); err != nil {
		return nil, err
	}
	return roster, nil
}

func (n *Node) chooseAutoName(ctx context.Context, beacon pairingBeacon) {
	if !n.usesAutoName() {
		return
	}
	taken := map[string]struct{}{}
	note := func(name, id string) {
		if strings.TrimSpace(id) == n.deviceID() {
			return
		}
		key := strings.ToLower(strings.TrimSpace(name))
		if key != "" {
			taken[key] = struct{}{}
		}
	}
	note(beacon.DeviceName, beacon.DeviceID)
	peers, err := n.fetchDevices(ctx, beacon.IPAddress, beacon.Port)
	if err != nil {
		log.Printf("Could not read peer names before choosing a cluster name: %v", err)
	}
	for _, peer := range peers {
		note(peer.DeviceName, peer.DeviceID)
	}
	name := pickDockerName(taken)
	if strings.EqualFold(name, n.deviceName()) {
		return
	}
	n.setAutoName(name)
	log.Printf("Cluster name is %s", name)
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

func (n *Node) announceSelf(ctx context.Context, eventKind string) {
	if !n.isJoined() || n.wasRemoved() {
		return
	}
	peers := n.roster()
	if len(peers) == 0 {
		return
	}
	if eventKind == "" {
		eventKind = "SELF_METADATA"
	}
	state := n.selfState()
	timeout := n.announceTimeout
	if timeout <= 0 {
		timeout = 3 * time.Second
	}
	var targets []deviceRecord
	for _, peer := range peers {
		if n.shouldDial(peer.LastKnownIP) && peer.Port > 0 {
			targets = append(targets, peer)
		}
	}
	// A phone that is asleep often needs longer than a quick first try, so
	// whoever stays silent gets one slower retry before it is reported.
	silent := n.announceRound(ctx, targets, state, eventKind, timeout)
	if len(silent) > 0 && ctx.Err() == nil {
		silent = n.announceRound(ctx, silent, state, eventKind, timeout*4)
	}
	n.logSilentPeers(silent)
}

// announceRound sends one announce to each peer and returns those that did not answer.
func (n *Node) announceRound(ctx context.Context, peers []deviceRecord, state nodeState, eventKind string, timeout time.Duration) []deviceRecord {
	var (
		wg     sync.WaitGroup
		mu     sync.Mutex
		silent []deviceRecord
	)
	for _, peer := range peers {
		wg.Add(1)
		go func(peer deviceRecord) {
			defer wg.Done()
			req := clusterSync{
				EventKind:      eventKind,
				NodeStates:     []nodeState{state},
				RemovedDevices: []removedRecord{},
				ClusterVersion: state.MembershipVersion,
				Devices:        []deviceRecord{},
			}
			body, status, err := n.postSync(ctx, peer, req, timeout)
			if err != nil || status < 200 || status > 299 {
				if err == nil {
					err = fmt.Errorf("HTTP %d", status)
				}
				if status != 0 {
					n.logAnnounceFailure(peer, err)
					return
				}
				mu.Lock()
				silent = append(silent, peer)
				mu.Unlock()
				return
			}
			var roster []deviceRecord
			if json.Unmarshal(body, &roster) == nil {
				n.adoptTrusted(roster)
			}
		}(peer)
	}
	wg.Wait()
	return silent
}

// logSilentPeers reports non-responders as one line, at most once per 10 minutes.
func (n *Node) logSilentPeers(silent []deviceRecord) {
	n.mu.Lock()
	defer n.mu.Unlock()
	var names []string
	for _, peer := range silent {
		if last, ok := n.announceLogged[peer.DeviceID]; ok && time.Since(last) < 10*time.Minute {
			continue
		}
		n.announceLogged[peer.DeviceID] = time.Now()
		names = append(names, peer.DeviceName)
	}
	if len(names) > 0 {
		sort.Strings(names)
		log.Printf("No answer to the announce from %d device(s): %s. They may be asleep or offline.", len(names), strings.Join(names, ", "))
	}
}

func (n *Node) postSync(ctx context.Context, peer deviceRecord, req clusterSync, timeout time.Duration) ([]byte, int, error) {
	payload, err := json.Marshal(req)
	if err != nil {
		return nil, 0, err
	}
	q := url.Values{}
	n.stampQuery(q)
	if pin := n.pinFor(peer.DeviceID); pin != "" {
		q.Set("pin", pin)
	}
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, peerURL(peer.LastKnownIP, peer.Port, "/api/v1/devices/merge", q), bytes.NewReader(payload))
	if err != nil {
		return nil, 0, err
	}
	httpReq.Header.Set("Content-Type", "application/json")
	id, mv := n.identityStamp()
	httpReq.Header.Set("X-FileApex-Device-Id", id)
	if pin := n.pinFor(peer.DeviceID); pin != "" {
		httpReq.Header.Set("X-FileApex-Pin", pin)
	}
	if mv > 0 {
		httpReq.Header.Set("X-FileApex-Membership-Version", fmt.Sprintf("%d", mv))
	}
	body, status, err := n.do(httpReq, timeout)
	return body, status, err
}

func (n *Node) stampQuery(q url.Values) {
	id, mv := n.identityStamp()
	q.Set("from", id)
	if mv > 0 {
		q.Set("mv", strconvFormat(mv))
	}
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
	ctx, cancel := context.WithTimeout(req.Context(), timeout)
	defer cancel()
	resp, err := client.Do(req.WithContext(ctx))
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if err != nil {
		return nil, resp.StatusCode, err
	}
	return body, resp.StatusCode, nil
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

func (n *Node) logAnnounceFailure(peer deviceRecord, err error) {
	n.mu.Lock()
	defer n.mu.Unlock()
	if last, ok := n.announceLogged[peer.DeviceID]; ok && time.Since(last) < 10*time.Minute {
		return
	}
	n.announceLogged[peer.DeviceID] = time.Now()
	log.Printf("Could not announce to %s: %v", peer.DeviceName, err)
}

func (n *Node) setPending(ids ...string) {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.pending = map[string]struct{}{}
	for _, id := range ids {
		id = strings.TrimSpace(id)
		if id != "" {
			n.pending[id] = struct{}{}
		}
	}
}

func (n *Node) drainSeed() {
	select {
	case <-n.seedNotify:
	default:
	}
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
	delete(n.tombstones, rec.DeviceID)
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
	if n.removed {
		return
	}
	n.joined = true
	n.persistLocked()
}

func (n *Node) ensureMembership() {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.membership > 0 {
		return
	}
	n.membership = time.Now().UnixMilli()
	n.persistLocked()
}

func (n *Node) applySync(req clusterSync) bool {
	now := time.Now().UnixMilli()
	n.mu.Lock()
	defer n.mu.Unlock()
	left := false
	for _, record := range req.RemovedDevices {
		if n.applyRemovalLocked(record, now) {
			left = true
		}
	}
	for _, state := range req.NodeStates {
		n.applyStateLocked(req.EventKind, state, now)
	}
	if req.EventKind == "PAIRING_INTRO" && len(n.pending) > 0 {
		select {
		case n.seedNotify <- struct{}{}:
		default:
		}
	}
	n.persistLocked()
	return left
}

func (n *Node) applyRemovalLocked(record removedRecord, now int64) bool {
	id := strings.TrimSpace(record.DeviceID)
	if id == "" || record.MembershipProtocol < membershipProtocol {
		return false
	}
	version := record.version()
	if !versionAcceptable(version, now) {
		return false
	}
	if id == n.identity.DeviceID {
		if n.removed && version <= n.removedVersion {
			return false
		}
		if !n.removed && (n.membership <= 0 || version <= n.membership) {
			return false
		}
		n.unjoinLocked(version)
		return true
	}
	known := int64(0)
	if tomb, ok := n.tombstones[id]; ok && tomb.Version > known {
		known = tomb.Version
	}
	if existing, ok := n.peers[id]; ok && existing.ClusterVersion > known {
		known = existing.ClusterVersion
	}
	if version <= known {
		return false
	}
	delete(n.peers, id)
	if n.tls != nil {
		n.tls.forget(id)
	}
	n.tombstones[id] = diskTomb{
		DeviceID:      id,
		PublicKeyHash: strings.TrimSpace(record.PublicKeyHash),
		Version:       version,
	}
	return false
}

func (n *Node) applyStateLocked(event string, state nodeState, now int64) {
	id := strings.TrimSpace(state.DeviceID)
	if id == "" || state.IsRemoved {
		return
	}
	if id == n.identity.DeviceID {
		if n.removed {
			return
		}
		if event == "PAIRING_INTRO" && state.MembershipProtocol >= membershipProtocol &&
			versionAcceptable(state.MembershipVersion, now) && state.MembershipVersion > n.membership {
			n.membership = state.MembershipVersion
		}
		return
	}
	version := state.ClusterVersion
	if state.MembershipProtocol >= membershipProtocol && state.MembershipVersion > 0 {
		version = state.MembershipVersion
	}
	canReinstate := state.MembershipProtocol >= membershipProtocol
	if !versionAcceptable(version, now) {
		if event != "PAIRING_INTRO" {
			return
		}
		version = now
		canReinstate = true
	}
	existing, exists := n.peers[id]
	if exists && version < existing.ClusterVersion {
		return
	}
	if n.blockedByTombstoneLocked(id, state.PublicKeyHash, version, canReinstate) {
		return
	}
	rec := deviceFromState(state, version)
	if parsed := net.ParseIP(rec.LastKnownIP); parsed == nil || parsed.IsLoopback() || parsed.IsUnspecified() {
		rec.LastKnownIP = ""
	}
	if exists {
		if rec.DeviceName == "" {
			rec.DeviceName = existing.DeviceName
		}
		if rec.LastKnownIP == "" {
			rec.LastKnownIP = existing.LastKnownIP
		}
		if rec.Port == 0 {
			rec.Port = existing.Port
		}
		if rec.RootPath == "" || rec.RootPath == "/" {
			if existing.RootPath != "" {
				rec.RootPath = existing.RootPath
			}
		}
		if rec.PublicKey == "" {
			rec.PublicKey = existing.PublicKey
			rec.E2eeEnabled = existing.E2eeEnabled
		}
		if rec.PublicKeyHash == "" {
			rec.PublicKeyHash = existing.PublicKeyHash
		}
	}
	if rec.DeviceName == "" {
		return
	}
	n.peers[id] = rec
}

func (n *Node) adoptTrusted(list []deviceRecord) {
	now := time.Now().UnixMilli()
	n.mu.Lock()
	defer n.mu.Unlock()
	changed := false
	for _, peer := range list {
		peer.DeviceID = strings.TrimSpace(peer.DeviceID)
		peer.DeviceName = strings.TrimSpace(peer.DeviceName)
		if peer.DeviceID == "" || peer.DeviceID == n.identity.DeviceID || peer.IsRemoved {
			continue
		}
		existing, exists := n.peers[peer.DeviceID]
		if peer.DeviceName == "" && !exists {
			continue
		}
		if n.blockedByTombstoneLocked(peer.DeviceID, peer.PublicKeyHash, peer.ClusterVersion, false) {
			continue
		}
		if !versionAcceptable(peer.ClusterVersion, now) {
			if exists {
				continue
			}
			peer.ClusterVersion = now
		}
		if exists && peer.ClusterVersion < existing.ClusterVersion {
			continue
		}
		if exists {
			if peer.DeviceName == "" {
				peer.DeviceName = existing.DeviceName
			}
			if peer.LastKnownIP == "" {
				peer.LastKnownIP = existing.LastKnownIP
			}
			if peer.Port == 0 {
				peer.Port = existing.Port
			}
			if peer.PublicKey == "" {
				peer.PublicKey = existing.PublicKey
				peer.E2eeEnabled = existing.E2eeEnabled
			}
			if peer.PublicKeyHash == "" {
				peer.PublicKeyHash = existing.PublicKeyHash
			}
			if peer.RootPath == "" {
				peer.RootPath = existing.RootPath
			}
		}
		peer.IsRemoved = false
		peer.RemovedAt = nil
		peer.LastSeenEpochMs = 0
		n.peers[peer.DeviceID] = peer
		changed = true
	}
	if changed {
		n.persistLocked()
	}
}

func (n *Node) blockedByTombstoneLocked(id, hash string, version int64, canReinstate bool) bool {
	tomb, ok := n.tombstones[id]
	if !ok && hash != "" {
		for _, candidate := range n.tombstones {
			if candidate.PublicKeyHash != "" && candidate.PublicKeyHash == hash {
				tomb = candidate
				ok = true
				break
			}
		}
	}
	if !ok || tomb.Version <= 0 {
		return false
	}
	now := time.Now().UnixMilli()
	if canReinstate && version > tomb.Version && versionAcceptable(version, now) {
		if hash == "" {
			delete(n.tombstones, id)
		} else {
			for key, candidate := range n.tombstones {
				if key == id || (candidate.PublicKeyHash != "" && candidate.PublicKeyHash == hash) {
					delete(n.tombstones, key)
				}
			}
		}
		return false
	}
	return true
}

func (n *Node) unjoinLocked(version int64) {
	if version > n.removedVersion {
		n.removedVersion = version
	}
	already := n.removed
	n.removed = true
	n.joined = false
	n.membership = 0
	n.peers = map[string]deviceRecord{}
	n.tombstones = map[string]diskTomb{}
	n.pins = map[string]string{}
	n.completed = nil
	if already {
		return
	}
	previous := n.sessionCh
	n.sessionCh = make(chan struct{})
	close(previous)
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

func (n *Node) isRevoked(id string, mv int64) bool {
	id = strings.TrimSpace(id)
	n.mu.Lock()
	defer n.mu.Unlock()
	if id == "" || id == n.identity.DeviceID {
		return false
	}
	tomb, ok := n.tombstones[id]
	if !ok || tomb.Version <= 0 {
		return false
	}
	if mv > tomb.Version && versionAcceptable(mv, time.Now().UnixMilli()) {
		return false
	}
	return true
}

func (n *Node) allowMerge(id string, mv int64) bool {
	id = strings.TrimSpace(id)
	if id == "" {
		return false
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	if _, ok := n.pending[id]; ok {
		return true
	}
	if peer, ok := n.peers[id]; ok && !peer.IsRemoved {
		return true
	}
	if tomb, ok := n.tombstones[id]; ok && mv > tomb.Version && versionAcceptable(mv, time.Now().UnixMilli()) {
		delete(n.tombstones, id)
		n.persistLocked()
		return true
	}
	return false
}

func (n *Node) allowUpload(id string, mv int64) bool {
	id = strings.TrimSpace(id)
	n.mu.Lock()
	defer n.mu.Unlock()
	if id == "" || id == n.identity.DeviceID {
		return false
	}
	if tomb, ok := n.tombstones[id]; ok && tomb.Version > 0 {
		if !(mv > tomb.Version && versionAcceptable(mv, time.Now().UnixMilli())) {
			return false
		}
	}
	peer, ok := n.peers[id]
	return ok && !peer.IsRemoved
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
	if !ok || peer.IsRemoved || peer.LastKnownIP == ip {
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
		if peer.IsRemoved || peer.DeviceID == "" || peer.DeviceID == n.identity.DeviceID {
			continue
		}
		peer.LastSeenEpochMs = 0
		out = append(out, peer)
	}
	sort.Slice(out, func(i, j int) bool {
		return strings.ToLower(out[i].DeviceName) < strings.ToLower(out[j].DeviceName)
	})
	return out
}

// publishedRoster is the list returned to other devices. This node does not
// keep or send last-seen; a caller records presence only from its own contacts.
func (n *Node) publishedRoster() []deviceRecord {
	peers := n.roster()
	for i := range peers {
		peers[i].LastSeenEpochMs = 0
	}
	return peers
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
	now := time.Now().UnixMilli()
	membership := n.membership
	cluster := membership
	if cluster <= 0 {
		cluster = now
	}
	if n.removed {
		membership = 0
		cluster = 0
	}
	return nodeState{
		DeviceID:           n.identity.DeviceID,
		DeviceName:         n.identity.DeviceName,
		IPAddress:          n.advertiseIPLocked(),
		Port:               n.port,
		ClientVersion:      "docker",
		Platform:           "linux",
		OS:                 "linux",
		DeviceMake:         "FileApex",
		DeviceModel:        "OMV",
		SupportedProtocols: supportedProtocols,
		RootPath:           n.inbox,
		PublicKeyHash:      fingerprint(n.identity.DeviceID),
		PublicKey:          n.pairPublicKey(),
		PinRequired:        false,
		DownloadsPath:      n.inbox,
		ClusterVersion:     cluster,
		MembershipVersion:  membership,
		MembershipProtocol: membershipProtocol,
		TLSPin:             n.tlsPinValue(),
		TLSPort:            n.tlsPort(),
	}
}

func (n *Node) selfRecord(ip string) deviceRecord {
	n.mu.Lock()
	defer n.mu.Unlock()
	now := time.Now().UnixMilli()
	version := n.membership
	if version <= 0 {
		version = now
	}
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
		ClusterVersion:         version,
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

func deviceFromState(state nodeState, version int64) deviceRecord {
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
	if version <= 0 {
		version = state.ClusterVersion
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
		ClusterVersion:         version,
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

func versionAcceptable(version, now int64) bool {
	return version > 0 && version <= now+maxForwardSkew.Milliseconds()
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
	if err := os.WriteFile(tmp, body, 0o644); err != nil {
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
	return n.tls.identity.pin
}
