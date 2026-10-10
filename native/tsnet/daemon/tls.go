package main

import (
	"bytes"
	"context"
	"crypto"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/hkdf"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Pinned mutual TLS for LAN peers. A peer is identified by the SHA-256 of its SubjectPublicKeyInfo.
// Pins reach this node only through an announcement sealed with the pairing key of an already paired
// device, the same scheme the apps use for clipboard encryption, so a LAN attacker cannot supply one.

const (
	tlsCertValidity   = 730 * 24 * time.Hour
	tlsCertRenewAfter = 700 * 24 * time.Hour
	tlsBackdate       = 24 * time.Hour
	pairHKDFInfo      = "FileApex-Clipboard-v1"
	announceMaxSkew   = 24 * time.Hour
	announceBodyLimit = 16 << 10
)

var pinFormat = regexp.MustCompile(`^[0-9a-f]{64}$`)

type tlsIdentity struct {
	cert tls.Certificate
	pin  string
}

func spkiPin(pub crypto.PublicKey) (string, error) {
	der, err := x509.MarshalPKIXPublicKey(pub)
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256(der)
	return hex.EncodeToString(sum[:]), nil
}

func writeSecretFile(path string, data []byte) error {
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func issueCert(key *ecdsa.PrivateKey, now time.Time) ([]byte, error) {
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 62))
	if err != nil {
		return nil, err
	}
	template := &x509.Certificate{
		SerialNumber:          serial.Add(serial, big.NewInt(1)),
		Subject:               pkix.Name{CommonName: "FileApex"},
		NotBefore:             now.Add(-tlsBackdate),
		NotAfter:              now.Add(tlsCertValidity),
		KeyUsage:              x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth, x509.ExtKeyUsageClientAuth},
		BasicConstraintsValid: true,
	}
	return x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
}

// loadOrCreateTLSIdentity keeps one P-256 key for the life of the data volume. The certificate is
// re-issued on the same key before it expires, so the pin never changes. An unreadable key file is
// an error: replacing it silently would change the pin without anyone knowing.
func loadOrCreateTLSIdentity(dir string, now time.Time) (*tlsIdentity, error) {
	keyPath := filepath.Join(dir, "tls_key.pem")
	certPath := filepath.Join(dir, "tls_cert.pem")
	var key *ecdsa.PrivateKey
	keyPEM, err := os.ReadFile(keyPath)
	switch {
	case errors.Is(err, os.ErrNotExist):
		key, err = ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
		if err != nil {
			return nil, err
		}
		der, err := x509.MarshalPKCS8PrivateKey(key)
		if err != nil {
			return nil, err
		}
		if err := writeSecretFile(keyPath, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der})); err != nil {
			return nil, err
		}
		_ = os.Remove(certPath)
	case err != nil:
		return nil, err
	default:
		block, _ := pem.Decode(keyPEM)
		if block == nil {
			return nil, errors.New("tls_key.pem is not PEM")
		}
		parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes)
		if err != nil {
			return nil, fmt.Errorf("tls_key.pem: %w", err)
		}
		var ok bool
		if key, ok = parsed.(*ecdsa.PrivateKey); !ok || key.Curve != elliptic.P256() {
			return nil, errors.New("tls_key.pem is not a P-256 key")
		}
	}
	var certDER []byte
	if certPEM, err := os.ReadFile(certPath); err == nil {
		if block, _ := pem.Decode(certPEM); block != nil {
			if leaf, err := x509.ParseCertificate(block.Bytes); err == nil && now.Before(leaf.NotBefore.Add(tlsCertRenewAfter+tlsBackdate)) {
				if same, _ := spkiPin(leaf.PublicKey); same != "" {
					if mine, _ := spkiPin(&key.PublicKey); mine == same {
						certDER = block.Bytes
					}
				}
			}
		}
	}
	if certDER == nil {
		if certDER, err = issueCert(key, now); err != nil {
			return nil, err
		}
		if err := writeSecretFile(certPath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: certDER})); err != nil {
			return nil, err
		}
	}
	pin, err := spkiPin(&key.PublicKey)
	if err != nil {
		return nil, err
	}
	return &tlsIdentity{cert: tls.Certificate{Certificate: [][]byte{certDER}, PrivateKey: key}, pin: pin}, nil
}

func loadOrCreatePairKey(dir string) (*ecdh.PrivateKey, error) {
	path := filepath.Join(dir, "pair_key")
	body, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		key, err := ecdh.X25519().GenerateKey(rand.Reader)
		if err != nil {
			return nil, err
		}
		return key, writeSecretFile(path, []byte(base64.StdEncoding.EncodeToString(key.Bytes())))
	}
	if err != nil {
		return nil, err
	}
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(string(body)))
	if err != nil {
		return nil, fmt.Errorf("pair_key: %w", err)
	}
	return ecdh.X25519().NewPrivateKey(raw)
}

func pairSalt(a, b string) string {
	ids := []string{strings.TrimSpace(a), strings.TrimSpace(b)}
	sort.Strings(ids)
	return strings.Join(ids, "|")
}

func pairKey(local *ecdh.PrivateKey, peerPublicB64, salt string) ([]byte, error) {
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(peerPublicB64))
	if err != nil || len(raw) != 32 {
		return nil, errors.New("invalid peer public key")
	}
	peer, err := ecdh.X25519().NewPublicKey(raw)
	if err != nil {
		return nil, err
	}
	shared, err := local.ECDH(peer)
	if err != nil {
		return nil, err
	}
	return hkdf.Key(sha256.New, shared, []byte(salt), pairHKDFInfo, 32)
}

// sealPair and openPair match ClipboardCrypto: base64(iv(12) || AES-256-GCM(ciphertext || tag)).
func sealPair(local *ecdh.PrivateKey, peerPublicB64, salt string, plain []byte) (string, error) {
	key, err := pairKey(local, peerPublicB64, salt)
	if err != nil {
		return "", err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	iv := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(iv); err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(gcm.Seal(iv, iv, plain, nil)), nil
}

func openPair(local *ecdh.PrivateKey, peerPublicB64, salt, payloadB64 string) ([]byte, error) {
	key, err := pairKey(local, peerPublicB64, salt)
	if err != nil {
		return nil, err
	}
	payload, err := base64.StdEncoding.DecodeString(strings.TrimSpace(payloadB64))
	if err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	if len(payload) <= gcm.NonceSize() {
		return nil, errors.New("payload too short")
	}
	return gcm.Open(nil, payload[:gcm.NonceSize()], payload[gcm.NonceSize():], nil)
}

type tlsPeer struct {
	Pin  string `json:"pin"`
	Port int    `json:"port"`
}

// tlsState is guarded by its own mutex so it can be used while Node.mu is held.
type tlsState struct {
	mu       sync.Mutex
	dir      string
	identity *tlsIdentity
	pairKey  *ecdh.PrivateKey
	peers    map[string]tlsPeer
	port     int
	server   *http.Server
	clients  map[string]*http.Client
	next     map[string]time.Time
}

func newTLSState(dir string, now time.Time) (*tlsState, error) {
	identity, err := loadOrCreateTLSIdentity(dir, now)
	if err != nil {
		return nil, err
	}
	pair, err := loadOrCreatePairKey(dir)
	if err != nil {
		return nil, err
	}
	t := &tlsState{dir: dir, identity: identity, pairKey: pair, peers: map[string]tlsPeer{}, clients: map[string]*http.Client{}, next: map[string]time.Time{}}
	if body, err := os.ReadFile(filepath.Join(dir, "tls_peers.json")); err == nil {
		if err := json.Unmarshal(body, &t.peers); err != nil {
			log.Printf("Ignoring unreadable tls_peers.json: %v", err)
			t.peers = map[string]tlsPeer{}
		}
	}
	return t, nil
}

func (t *tlsState) persistLocked() {
	body, err := json.MarshalIndent(t.peers, "", "  ")
	if err == nil {
		err = writeSecretFile(filepath.Join(t.dir, "tls_peers.json"), body)
	}
	if err != nil {
		log.Printf("Could not save TLS peers: %v", err)
	}
}

func (t *tlsState) publicKeyB64() string {
	return base64.StdEncoding.EncodeToString(t.pairKey.PublicKey().Bytes())
}

func (t *tlsState) peer(id string) (tlsPeer, bool) {
	t.mu.Lock()
	defer t.mu.Unlock()
	p, ok := t.peers[strings.TrimSpace(id)]
	return p, ok
}

func (t *tlsState) deviceForPin(pin string) string {
	t.mu.Lock()
	defer t.mu.Unlock()
	for id, p := range t.peers {
		if subtle.ConstantTimeCompare([]byte(p.Pin), []byte(pin)) == 1 {
			return id
		}
	}
	return ""
}

func (t *tlsState) record(id, pin string, port int) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.peers[id] = tlsPeer{Pin: pin, Port: port}
	delete(t.clients, id)
	t.persistLocked()
}

func (t *tlsState) forget(id string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if _, ok := t.peers[id]; ok {
		delete(t.peers, id)
		delete(t.clients, id)
		t.persistLocked()
	}
}

func (t *tlsState) forgetAll() {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.peers = map[string]tlsPeer{}
	t.clients = map[string]*http.Client{}
	t.persistLocked()
}

// requiresTLS lists what a pinned peer may not do over plain HTTP. Pairing, presence and the pin
// exchange stay open so a peer that lost our pin can still be seen and re-announce.
func requiresTLS(path string) bool {
	if i := strings.IndexByte(path, '?'); i >= 0 {
		path = path[:i]
	}
	if path == "/api/v1/identity/rename" {
		return true
	}
	for _, open := range []string{"/api/v1/pairing", "/api/v1/auth", "/api/v1/tls/pin", "/api/v1/identity", "/api/v1/heartbeat", "/api/v1/health"} {
		if path == open || strings.HasPrefix(path, open+"/") {
			return false
		}
	}
	return true
}

func (t *tlsState) serverConfig() *tls.Config {
	return &tls.Config{
		Certificates: []tls.Certificate{t.identity.cert},
		ClientAuth:   tls.RequireAnyClientCert,
		MinVersion:   tls.VersionTLS12,
		VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
			if len(raw) == 0 {
				return errors.New("no client certificate")
			}
			leaf, err := x509.ParseCertificate(raw[0])
			if err != nil {
				return err
			}
			pin, err := leafPin(leaf)
			if err != nil {
				return err
			}
			if t.deviceForPin(pin) == "" {
				return errors.New("client key is not pinned")
			}
			return nil
		},
	}
}

func leafPin(leaf *x509.Certificate) (string, error) {
	pub, ok := leaf.PublicKey.(*ecdsa.PublicKey)
	if !ok || pub.Curve != elliptic.P256() {
		return "", errors.New("unsupported key type")
	}
	return spkiPin(pub)
}

func (t *tlsState) clientConfig(pins map[string]bool) *tls.Config {
	return &tls.Config{
		Certificates:       []tls.Certificate{t.identity.cert},
		InsecureSkipVerify: true, // identity is the pin below, not a name or a CA
		MinVersion:         tls.VersionTLS12,
		VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
			if len(raw) == 0 {
				return errors.New("no server certificate")
			}
			leaf, err := x509.ParseCertificate(raw[0])
			if err != nil {
				return err
			}
			pin, err := leafPin(leaf)
			if err != nil || !pins[pin] {
				return errors.New("peer key does not match its pin")
			}
			return nil
		},
	}
}

// ---- server side ----

func (n *Node) serveTLS() {
	t := n.tls
	if t == nil {
		return
	}
	n.mu.Lock()
	preferred := n.port + 1
	n.mu.Unlock()
	if v, err := strconv.Atoi(strings.TrimSpace(os.Getenv("FILEAPEX_TLS_PORT"))); err == nil && v > 0 && v < 65536 {
		preferred = v
	}
	ln, err := net.Listen("tcp", fmt.Sprintf(":%d", preferred))
	if err != nil {
		ln, err = net.Listen("tcp", ":0")
	}
	if err != nil {
		log.Printf("TLS listener unavailable, serving HTTP only: %v", err)
		return
	}
	srv := &http.Server{
		Handler:           n.guard(n.routes(), true),
		ReadHeaderTimeout: 30 * time.Second,
		TLSConfig:         t.serverConfig(),
	}
	t.mu.Lock()
	t.server = srv
	t.port = ln.Addr().(*net.TCPAddr).Port
	t.mu.Unlock()
	go func() {
		if err := srv.Serve(tls.NewListener(ln, t.serverConfig())); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Printf("TLS server stopped: %v", err)
		}
	}()
	log.Printf("TLS listening on %s, key fingerprint %s (check it on the other device)", ln.Addr(), fingerprintOfPin(t.identity.pin))
}

func fingerprintOfPin(pin string) string {
	if len(pin) < 16 {
		return pin
	}
	return pin[0:4] + "-" + pin[4:8] + "-" + pin[8:12] + "-" + pin[12:16]
}

func (n *Node) tlsPort() int {
	if n.tls == nil {
		return 0
	}
	n.tls.mu.Lock()
	defer n.tls.mu.Unlock()
	return n.tls.port
}

func (n *Node) shutdownTLS(timeout time.Duration) {
	if n.tls == nil {
		return
	}
	n.tls.mu.Lock()
	srv := n.tls.server
	n.tls.mu.Unlock()
	if srv == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	_ = srv.Shutdown(ctx)
}

// guard enforces the transport rules in front of every route. On TLS the device is the one whose
// certificate was presented; a different `from` is refused. On plain HTTP a device that has a pin
// may only use the routes in requiresTLS's allow list.
func (n *Node) guard(next http.Handler, viaTLS bool) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		claimed, _ := requestSender(r)
		if viaTLS {
			var certID string
			if n.tls != nil && r.TLS != nil && len(r.TLS.PeerCertificates) > 0 {
				if pin, err := leafPin(r.TLS.PeerCertificates[0]); err == nil {
					certID = n.tls.deviceForPin(pin)
				}
			}
			if certID == "" || (claimed != "" && claimed != certID) {
				log.Printf("Rejected TLS request %s: identity mismatch", r.URL.Path)
				http.Error(w, "peer_identity_mismatch", http.StatusForbidden)
				return
			}
			r.Header.Set("X-FileApex-Device-Id", certID)
		} else if n.tls != nil && claimed != "" && requiresTLS(r.URL.Path) {
			if _, pinned := n.tls.peer(claimed); pinned {
				log.Printf("Rejected plain HTTP %s from pinned peer %s", r.URL.Path, claimed)
				http.Error(w, "tls_required", http.StatusForbidden)
				return
			}
		}
		next.ServeHTTP(w, r)
	})
}

// ---- pin announcements ----

type tlsAnnouncement struct {
	SenderDeviceID  string `json:"senderDeviceId"`
	SenderPublicKey string `json:"senderPublicKey"`
	Ciphertext      string `json:"ciphertext"`
}

type tlsPayload struct {
	Pin             string `json:"pin"`
	TLSPort         int    `json:"tlsPort"`
	IssuedAtEpochMs int64  `json:"issuedAtEpochMs"`
}

func (n *Node) peerRecord(id string) (deviceRecord, bool) {
	for _, peer := range n.roster() {
		if peer.DeviceID == id {
			return peer, true
		}
	}
	return deviceRecord{}, false
}

func (n *Node) buildAnnouncement(peer deviceRecord, now time.Time) (tlsAnnouncement, bool) {
	port := n.tlsPort()
	if n.tls == nil || port <= 0 || strings.TrimSpace(peer.PublicKey) == "" {
		return tlsAnnouncement{}, false
	}
	body, err := json.Marshal(tlsPayload{Pin: n.tls.identity.pin, TLSPort: port, IssuedAtEpochMs: now.UnixMilli()})
	if err != nil {
		return tlsAnnouncement{}, false
	}
	self := n.deviceID()
	sealed, err := sealPair(n.tls.pairKey, peer.PublicKey, pairSalt(self, peer.DeviceID), body)
	if err != nil {
		return tlsAnnouncement{}, false
	}
	return tlsAnnouncement{SenderDeviceID: self, SenderPublicKey: n.tls.publicKeyB64(), Ciphertext: sealed}, true
}

type announceOutcome int

const (
	announceRecorded announceOutcome = iota
	announceUnchanged
	announceKeyChanged
	announceUnknownPeer
	announceUntrusted
	announceInvalid
)

// acceptAnnouncement trusts the sender only through the pairing key on record for that device.
// A first pin is stored; a different pin for a pinned peer is logged and refused, because this node
// has no one to ask.
func (n *Node) acceptAnnouncement(a tlsAnnouncement, now time.Time) announceOutcome {
	if n.tls == nil {
		return announceInvalid
	}
	peer, ok := n.peerRecord(strings.TrimSpace(a.SenderDeviceID))
	if !ok {
		return announceUnknownPeer
	}
	if strings.TrimSpace(peer.PublicKey) == "" || strings.TrimSpace(peer.PublicKey) != strings.TrimSpace(a.SenderPublicKey) {
		return announceUntrusted
	}
	plain, err := openPair(n.tls.pairKey, peer.PublicKey, pairSalt(n.deviceID(), peer.DeviceID), a.Ciphertext)
	if err != nil {
		return announceInvalid
	}
	var payload tlsPayload
	if err := json.Unmarshal(plain, &payload); err != nil {
		return announceInvalid
	}
	pin := strings.ToLower(payload.Pin)
	skew := now.Sub(time.UnixMilli(payload.IssuedAtEpochMs))
	if !pinFormat.MatchString(pin) || payload.TLSPort < 1 || payload.TLSPort > 65535 || skew > announceMaxSkew || skew < -announceMaxSkew {
		return announceInvalid
	}
	existing, pinned := n.tls.peer(peer.DeviceID)
	switch {
	case !pinned:
		n.tls.record(peer.DeviceID, pin, payload.TLSPort)
		log.Printf("Pinned %s (key %s)", n.peerLabel(peer.DeviceID), fingerprintOfPin(pin))
		return announceRecorded
	case existing.Pin == pin:
		if existing.Port != payload.TLSPort {
			n.tls.record(peer.DeviceID, pin, payload.TLSPort)
		}
		return announceUnchanged
	default:
		log.Printf("%s announced a different key (%s); keeping the pinned one. Use --reset or re-pair after checking the device.", n.peerLabel(peer.DeviceID), fingerprintOfPin(pin))
		return announceKeyChanged
	}
}

func (n *Node) handleTLSPin(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, announceBodyLimit+1))
	if err != nil || len(body) > announceBodyLimit {
		http.Error(w, "tls_pin_invalid", http.StatusBadRequest)
		return
	}
	var a tlsAnnouncement
	if err := json.Unmarshal(body, &a); err != nil {
		http.Error(w, "tls_pin_invalid", http.StatusBadRequest)
		return
	}
	now := time.Now()
	switch n.acceptAnnouncement(a, now) {
	case announceUnknownPeer, announceUntrusted:
		http.Error(w, "tls_pin_not_trusted", http.StatusForbidden)
		return
	case announceInvalid:
		http.Error(w, "tls_pin_invalid", http.StatusBadRequest)
		return
	}
	peer, _ := n.peerRecord(strings.TrimSpace(a.SenderDeviceID))
	reply, ok := n.buildAnnouncement(peer, now)
	if !ok {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	writeJSON(w, http.StatusOK, reply)
}

// handleClipboardStatus tells apps this node has no clipboard, so none is ever sent to it.
func (n *Node) handleClipboardStatus(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"sharingEnabled": false, "deviceId": n.deviceID(), "deviceName": n.deviceName()})
}

func (n *Node) handleClipboardSend(w http.ResponseWriter, r *http.Request) {
	http.Error(w, "clipboard_disabled", http.StatusForbidden)
}

// ---- client side ----

type tlsRoute struct {
	id      string
	pins    map[string]bool
	tlsPort int
}

func (n *Node) tlsRouteFor(host string, port int) (tlsRoute, bool) {
	if n.tls == nil {
		return tlsRoute{}, false
	}
	for _, peer := range n.roster() {
		if peer.LastKnownIP != host || peer.Port != port {
			continue
		}
		if p, ok := n.tls.peer(peer.DeviceID); ok {
			return tlsRoute{id: peer.DeviceID, pins: map[string]bool{p.Pin: true}, tlsPort: p.Port}, true
		}
	}
	return tlsRoute{}, false
}

func (n *Node) tlsClientFor(route tlsRoute) *http.Client {
	t := n.tls
	t.mu.Lock()
	defer t.mu.Unlock()
	if c, ok := t.clients[route.id]; ok {
		return c
	}
	cfg := t.clientConfig(route.pins)
	c := &http.Client{Transport: &http.Transport{
		Proxy: nil,
		DialTLSContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			raw, err := n.dial(ctx, network, addr)
			if err != nil {
				return nil, err
			}
			conn := tls.Client(raw, cfg)
			if err := conn.HandshakeContext(ctx); err != nil {
				raw.Close()
				return nil, err
			}
			return conn, nil
		},
		ResponseHeaderTimeout: 20 * time.Second,
		IdleConnTimeout:       30 * time.Second,
	}}
	t.clients[route.id] = c
	return c
}

// clientFor returns the client and request for a peer: TLS to its pinned port when it has a pin,
// the plain client otherwise. A pinned peer is never contacted over HTTP.
func (n *Node) clientFor(req *http.Request) (*http.Client, *http.Request) {
	host := req.URL.Hostname()
	port := 0
	fmt.Sscanf(req.URL.Port(), "%d", &port)
	route, ok := n.tlsRouteFor(host, port)
	if !ok {
		return n.httpClient, req
	}
	clone := req.Clone(req.Context())
	clone.URL = &url.URL{Scheme: "https", Host: net.JoinHostPort(host, fmt.Sprintf("%d", route.tlsPort)), Path: req.URL.Path, RawQuery: req.URL.RawQuery}
	clone.Host = ""
	if req.GetBody != nil {
		clone.GetBody = req.GetBody
	}
	return n.tlsClientFor(route), clone
}

// announceTo sends this node's pin to one peer and stores the pin in the reply.
func (n *Node) announceTLSTo(ctx context.Context, peer deviceRecord, now time.Time) error {
	a, ok := n.buildAnnouncement(peer, now)
	if !ok {
		return errors.New("nothing to announce")
	}
	payload, err := json.Marshal(a)
	if err != nil {
		return err
	}
	q := url.Values{}
	n.stampQuery(q)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, peerURL(peer.LastKnownIP, peer.Port, "/api/v1/tls/pin", q), bytes.NewReader(payload))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	// The pin exchange is the one call a peer without our pin must be able to make, so it goes over HTTP.
	body, status, err := n.doPlain(req, 10*time.Second)
	if err != nil {
		return err
	}
	switch {
	case status == http.StatusNoContent:
		return nil
	case status == http.StatusNotFound:
		return errTLSUnsupported
	case status < 200 || status > 299:
		return fmt.Errorf("HTTP %d", status)
	}
	var reply tlsAnnouncement
	if err := json.Unmarshal(body, &reply); err != nil {
		return err
	}
	n.acceptAnnouncement(reply, time.Now())
	return nil
}

var errTLSUnsupported = errors.New("peer has no TLS support")

// tlsAnnounceLoop brings paired peers onto TLS: every peer with a pairing key and no pin yet is
// told this node's pin. Peers on older builds answer 404 and are left alone for a while.
func (n *Node) tlsAnnounceLoop(ctx context.Context) {
	if n.tls == nil {
		return
	}
	ticker := time.NewTicker(20 * time.Second)
	defer ticker.Stop()
	for {
		n.tlsAnnounceRound(ctx)
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

func (n *Node) tlsAnnounceRound(ctx context.Context) {
	if n.tlsPort() <= 0 {
		return
	}
	now := time.Now()
	for _, peer := range n.roster() {
		if peer.IsRemoved || strings.TrimSpace(peer.PublicKey) == "" || !isPrivateLAN(peer.LastKnownIP) || peer.Port <= 0 {
			continue
		}
		if _, pinned := n.tls.peer(peer.DeviceID); pinned {
			continue
		}
		n.tls.mu.Lock()
		due := n.tls.next[peer.DeviceID]
		n.tls.mu.Unlock()
		if now.Before(due) {
			continue
		}
		wait := time.Minute
		if err := n.announceTLSTo(ctx, peer, now); err == nil {
			wait = 10 * time.Minute
		} else if errors.Is(err, errTLSUnsupported) {
			wait = 30 * time.Minute
		}
		n.tls.mu.Lock()
		n.tls.next[peer.DeviceID] = now.Add(wait)
		n.tls.mu.Unlock()
	}
}
