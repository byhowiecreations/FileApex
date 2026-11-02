package main

import (
	"crypto/ecdsa"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"os"
	"strings"
	"time"
)

const maxChainBody = 512 * 1024

// chainFile is what chain.json holds: every record this node stores, losing fork branches included, and what it
// remembers about its own in-person pairing so a restart between the pairing and the next tick does not lose it.
type chainFile struct {
	Records    []chainRecord          `json:"records"`
	PairedAtMs int64                  `json:"pairedAtMs,omitempty"`
	Holds      map[string]heldRemoval `json:"holds,omitempty"`
}

// chainAnswer is the same JSON the Kotlin ChainAnswer reads and writes.
type chainAnswer struct {
	Head     string        `json:"head"`
	Records  []chainRecord `json:"records"`
	Profiles []nodeState   `json:"profiles"`
}

type chainPush struct {
	Records  []chainRecord `json:"records"`
	Profiles []nodeState   `json:"profiles"`
}

type chainJoinRequest struct {
	Key     string     `json:"key"`
	TLSPin  string     `json:"tlsPin"`
	Profile *nodeState `json:"profile"`
}

func (n *Node) signer() (*ecdsa.PrivateKey, string) {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.signing, spkiBase64(&n.signing.PublicKey)
}

func (l *chainLog) all() []chainRecord {
	l.mu.Lock()
	defer l.mu.Unlock()
	return append([]chainRecord(nil), l.records...)
}

// loadChain reads chain.json. A file that cannot be read starts the chain empty: a peer's answer refills it, and
// nothing is overwritten until the chain changes.
func (n *Node) loadChain() {
	body, err := os.ReadFile(n.chainPath)
	if errors.Is(err, os.ErrNotExist) {
		return
	}
	if err != nil {
		log.Printf("Ignoring unreadable chain file: %v", err)
		return
	}
	var file chainFile
	if err := json.Unmarshal(body, &file); err != nil {
		log.Printf("Ignoring unreadable chain file: %v", err)
		return
	}
	for _, record := range file.Records {
		if verifyRecord(record) {
			n.chain.rawPut(record)
		}
	}
	if file.PairedAtMs > 0 {
		n.pairedAt = time.UnixMilli(file.PairedAtMs)
	}
	n.applier.restore(file.Holds)
}

func (n *Node) saveChain() {
	file := chainFile{Records: n.chain.all(), Holds: n.applier.snapshot()}
	n.mu.Lock()
	if !n.pairedAt.IsZero() {
		file.PairedAtMs = n.pairedAt.UnixMilli()
	}
	n.mu.Unlock()
	if err := writeJSONFile(n.chainPath, file); err != nil {
		log.Printf("Could not save the membership chain: %v", err)
	}
}

// chainCaller is the device whose certificate is on this connection. A plain HTTP request has none.
func (n *Node) chainCaller(r *http.Request) (id, pin string, ok bool) {
	if n.tls == nil || r.TLS == nil || len(r.TLS.PeerCertificates) == 0 {
		return "", "", false
	}
	pin, err := leafPin(r.TLS.PeerCertificates[0])
	if err != nil {
		return "", "", false
	}
	id = n.tls.deviceForPin(pin)
	return id, pin, id != ""
}

// ownProfile and the profiles of the active members this node knows: names, addresses and ports, never membership.
func (n *Node) outgoingProfiles() []nodeState {
	active := map[string]bool{}
	for _, id := range n.chain.view().State.activeIDs() {
		active[id] = true
	}
	out := []nodeState{n.selfState()}
	for _, peer := range n.roster() {
		if active[peer.DeviceID] {
			out = append(out, stateFromRecord(peer))
		}
	}
	return out
}

func stateFromRecord(peer deviceRecord) nodeState {
	protocols := []string{}
	_ = json.Unmarshal([]byte(peer.SupportedProtocolsJSON), &protocols)
	if protocols == nil {
		protocols = []string{}
	}
	return nodeState{
		DeviceID: peer.DeviceID, DeviceName: peer.DeviceName, IPAddress: peer.LastKnownIP, Port: peer.Port,
		ClientVersion: peer.ClientVersion, ClientVersionCode: peer.ClientVersionCode, Platform: peer.Platform, OS: peer.OS,
		DeviceMake: peer.DeviceMake, DeviceModel: peer.DeviceModel, SupportedProtocols: protocols, RootPath: peer.RootPath,
		PublicKeyHash: peer.PublicKeyHash, PublicKey: peer.PublicKey,
	}
}

// takeProfiles fills in the roster entry of a device the verified chain holds as active. A profile never decides who
// is a member and never sets a TLS pin.
func (n *Node) takeProfiles(states []nodeState) {
	active := map[string]bool{}
	for _, id := range n.chain.view().State.activeIDs() {
		active[id] = true
	}
	self := n.deviceID()
	changed := false
	n.mu.Lock()
	for _, state := range states {
		id := strings.TrimSpace(state.DeviceID)
		if id == "" || id == self || !active[id] || state.IsRemoved {
			continue
		}
		existing := n.peers[id]
		rec := deviceFromState(state)
		if rec.DeviceName == "" {
			rec.DeviceName = existing.DeviceName
		}
		if rec.DeviceName == "" {
			continue
		}
		if rec.LastKnownIP == "" {
			rec.LastKnownIP = existing.LastKnownIP
		}
		if rec.Port == 0 {
			rec.Port = existing.Port
		}
		if rec.PublicKey == "" {
			rec.PublicKey, rec.E2eeEnabled = existing.PublicKey, existing.E2eeEnabled
		}
		if rec.PublicKeyHash == "" {
			rec.PublicKeyHash = existing.PublicKeyHash
		}
		if rec.RootPath == "" || rec.RootPath == "/" {
			if existing.RootPath != "" {
				rec.RootPath = existing.RootPath
			}
		}
		n.peers[id] = rec
		changed = true
		if state.TLSPort > 0 && n.tls != nil {
			if pinned, ok := n.tls.peer(id); ok && !pinned.Revoked && pinned.Port != state.TLSPort {
				n.tls.record(id, pinned.Pin, state.TLSPort)
			}
		}
	}
	if changed {
		n.persistLocked()
	}
	n.mu.Unlock()
}

func (n *Node) answerChain(withProfiles bool) chainAnswer {
	answer := chainAnswer{Head: n.chain.view().State.Head, Records: n.chain.recordsAfter(0), Profiles: []nodeState{}}
	if answer.Records == nil {
		answer.Records = []chainRecord{}
	}
	if withProfiles {
		answer.Profiles = n.outgoingProfiles()
	}
	return answer
}

func (n *Node) onRoster(id string) bool {
	n.mu.Lock()
	defer n.mu.Unlock()
	_, ok := n.peers[id]
	return ok
}

func (n *Node) handleChain(w http.ResponseWriter, r *http.Request) {
	caller, _, ok := n.chainCaller(r)
	if !ok {
		http.Error(w, "authentication_required", http.StatusUnauthorized)
		return
	}
	revoked := n.tls.isRevoked(caller)
	switch r.Method {
	case http.MethodGet:
		if !revoked && !n.onRoster(caller) {
			http.Error(w, "peer_not_paired", http.StatusForbidden)
			return
		}
		writeJSON(w, http.StatusOK, n.answerChain(!revoked))
	case http.MethodPost:
		if revoked {
			w.Header().Set("X-FileApex-Status", "revoked")
			http.Error(w, "revoked", http.StatusUnauthorized)
			return
		}
		if !n.onRoster(caller) {
			http.Error(w, "peer_not_paired", http.StatusForbidden)
			return
		}
		body, err := io.ReadAll(io.LimitReader(r.Body, maxChainBody))
		var push chainPush
		if err != nil || json.Unmarshal(body, &push) != nil {
			http.Error(w, "chain_push_failed", http.StatusBadRequest)
			return
		}
		n.receiveChain(push.Records, push.Profiles)
		writeJSON(w, http.StatusOK, n.answerChain(true))
	default:
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
	}
}

// receiveChain keeps what verifies, saves it and lets the node follow it.
func (n *Node) receiveChain(records []chainRecord, profiles []nodeState) {
	before := n.chain.view().State.Head
	n.chain.ingest(records)
	if n.chain.view().State.Head != before {
		n.saveChain()
		n.applyChainNow()
	}
	n.takeProfiles(profiles)
}

func (n *Node) handleChainJoin(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	caller, pin, ok := n.chainCaller(r)
	if !ok {
		http.Error(w, "authentication_required", http.StatusUnauthorized)
		return
	}
	if n.tls.isRevoked(caller) {
		w.Header().Set("X-FileApex-Status", "revoked")
		http.Error(w, "revoked", http.StatusUnauthorized)
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, maxChainBody))
	var request chainJoinRequest
	if err != nil || json.Unmarshal(body, &request) != nil {
		http.Error(w, "chain_join_failed", http.StatusBadRequest)
		return
	}
	// The id and the pin are the certificate's; the pin in the body is a claim and is not used.
	if n.onRoster(caller) {
		n.admit(caller, request.Key, pin)
		if request.Profile != nil {
			n.takeProfiles([]nodeState{*request.Profile})
		}
	}
	writeJSON(w, http.StatusOK, n.answerChain(true))
}

// admit writes an ADMIT for a device of this node's roster. The chain's own rules decide whether this node may.
func (n *Node) admit(id, key, pin string) bool {
	if strings.TrimSpace(key) == "" {
		return false
	}
	signing, _ := n.signer()
	if _, err := n.chain.append(signing, n.deviceID(), kindAdmit, id, key, pin, reasonPaired); err != nil {
		log.Printf("Join from %s refused: %v", n.peerLabel(id), err)
		return false
	}
	n.saveChain()
	return true
}
