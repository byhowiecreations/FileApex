package main

import (
	"bytes"
	"context"
	"encoding/json"
	"log"
	"net/http"
	"strings"
	"time"
)

const chainPath = "/api/v1/membership/chain"
const chainRequestTimeout = 8 * time.Second

// chainEffectsOf is what the chain changes on this node.
type chainEffectsOf struct{ n *Node }

func (e chainEffectsOf) knownPin(id string) (string, bool) {
	if e.n.tls == nil {
		return "", false
	}
	p, ok := e.n.tls.peer(id)
	if !ok || p.Revoked {
		return "", false
	}
	return p.Pin, true
}

func (e chainEffectsOf) setPin(id, pin string) {
	if e.n.tls == nil {
		return
	}
	port := 0
	if p, ok := e.n.tls.peer(id); ok {
		port = p.Port
	}
	e.n.tls.record(id, pin, port)
}

func (e chainEffectsOf) ensureProfile(id string) {
	e.n.mu.Lock()
	defer e.n.mu.Unlock()
	if _, ok := e.n.peers[id]; ok {
		return
	}
	e.n.peers[id] = deviceRecord{DeviceID: id, DeviceName: "Device " + shortID(id), RootPath: "/"}
	e.n.persistLocked()
}

func shortID(id string) string {
	id = strings.TrimPrefix(id, "fx-")
	if len(id) > 6 {
		return id[:6]
	}
	return id
}

func (e chainEffectsOf) selfRemoved(by, hash string) {
	log.Printf("This node was removed from the cluster by %s. It lets go of the cluster and listens for a new pairing.", e.n.peerLabel(by))
	e.n.wipeCluster()
}

func (e chainEffectsOf) handledRemoval() string {
	e.n.mu.Lock()
	defer e.n.mu.Unlock()
	return e.n.handledRemoval
}

func (e chainEffectsOf) markHandled(hash string) {
	e.n.mu.Lock()
	defer e.n.mu.Unlock()
	e.n.handledRemoval = hash
}

func (e chainEffectsOf) knownRemoved(id string) bool {
	return e.n.tls != nil && e.n.tls.isRevoked(id) && !e.n.onRoster(id)
}

func (e chainEffectsOf) markRemoved(id, by, reason, pin string) {
	n := e.n
	n.mu.Lock()
	delete(n.peers, id)
	n.persistLocked()
	n.mu.Unlock()
	if n.tls == nil {
		return
	}
	if _, ok := n.tls.peer(id); !ok && pinOK(pin) && pin != "" {
		n.tls.record(id, pin, 0)
	}
	n.tls.revoke(id)
	log.Printf("%s was %s the cluster.", shortID(id), map[string]string{reasonUserRemoved: "removed from", reasonLeft: "released from"}[reason])
}

// applyChainNow walks the chain and lets this node follow it: pins, roster, a removal of this node.
func (n *Node) applyChainNow() {
	n.applier.apply(n.chain.view())
}

// wipeCluster lets go of everything of the cluster and makes new keys: the chain never takes a removed key back.
func (n *Node) wipeCluster() {
	n.mu.Lock()
	n.joined = false
	n.peers = map[string]deviceRecord{}
	n.pins = map[string]string{}
	n.completed = nil
	previous := n.sessionCh
	n.sessionCh = make(chan struct{})
	n.persistLocked()
	n.mu.Unlock()
	select {
	case <-previous:
	default:
		close(previous)
	}
	n.chain.clear()
	n.saveChain()
	if n.tls != nil {
		n.tls.forgetAll()
		if _, err := n.tls.renewIdentity(time.Now()); err != nil {
			log.Printf("Could not renew the TLS key: %v", err)
		}
	}
	n.renewSigning()
}

func (n *Node) renewSigning() {
	key, err := renewSigningKey(n.dataDir)
	if err != nil {
		log.Printf("Could not renew the signing key: %v", err)
		return
	}
	n.mu.Lock()
	n.signing = key
	n.mu.Unlock()
}

// ---- the wire to a peer ----

func (n *Node) chainCall(ctx context.Context, peer deviceRecord, method, path string, body any) (chainAnswer, bool) {
	var reader *bytes.Reader
	if body != nil {
		payload, err := json.Marshal(body)
		if err != nil {
			return chainAnswer{}, false
		}
		reader = bytes.NewReader(payload)
	} else {
		reader = bytes.NewReader(nil)
	}
	req, err := http.NewRequestWithContext(ctx, method, peerURL(peer.LastKnownIP, peer.Port, path, nil), reader)
	if err != nil {
		return chainAnswer{}, false
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	raw, status, err := n.do(req, chainRequestTimeout)
	if err != nil || status < 200 || status > 299 {
		return chainAnswer{}, false
	}
	var answer chainAnswer
	if json.Unmarshal(raw, &answer) != nil {
		return chainAnswer{}, false
	}
	return answer, true
}

func (n *Node) rosterPeer(id string) (deviceRecord, bool) {
	n.mu.Lock()
	defer n.mu.Unlock()
	peer, ok := n.peers[id]
	return peer, ok && peer.LastKnownIP != "" && peer.Port > 0
}

func (n *Node) pushChain(ctx context.Context, peer deviceRecord) {
	n.chainCall(ctx, peer, http.MethodPost, chainPath, chainPush{Records: n.chain.recordsAfter(0), Profiles: n.outgoingProfiles()})
}

// announceChain pushes the chain to the other active members at once; the rest catch up by pulling.
func (n *Node) announceChain(ctx context.Context, exceptIDs ...string) {
	skip := map[string]bool{n.deviceID(): true}
	for _, id := range exceptIDs {
		skip[id] = true
	}
	for _, id := range n.chain.view().State.activeIDs() {
		if skip[id] {
			continue
		}
		if peer, ok := n.rosterPeer(id); ok {
			n.pushChain(ctx, peer)
		}
	}
}

// reconcile: true when the peer answered. Takes in what it has and sends it what it lacks.
func (n *Node) reconcile(ctx context.Context, peer deviceRecord) bool {
	theirs, ok := n.chainCall(ctx, peer, http.MethodGet, chainPath, nil)
	if !ok {
		return false
	}
	before := n.chain.view().State.Head
	n.chain.ingest(theirs.Records)
	after := n.chain.view().State.Head
	if after != before {
		n.saveChain()
		n.applyChainNow()
	}
	n.takeProfiles(theirs.Profiles)
	if after != theirs.Head && n.isJoined() {
		n.pushChain(ctx, peer)
	}
	return true
}

// ---- pairing in person and the tick ----

// notePairedInPerson: for a short while a removal of this node under its current key may be answered with a new key.
func (n *Node) notePairedInPerson() {
	n.mu.Lock()
	n.pairedAt = time.Now()
	n.mu.Unlock()
	n.saveChain()
}

func (n *Node) pairedRecently() bool {
	n.mu.Lock()
	defer n.mu.Unlock()
	return !n.pairedAt.IsZero() && time.Since(n.pairedAt) <= pairingGrace
}

// joinClusterOf: the user paired this node into the cluster of hostID in person, so that cluster wins. When the host's
// chain has another root this node's own chain is dropped first, and a removal of this node already in the host's
// chain is marked as history before anything applies it.
func (n *Node) joinClusterOf(ctx context.Context, hostID string) bool {
	host, ok := n.rosterPeer(hostID)
	if !ok {
		return false
	}
	theirs, ok := n.chainCall(ctx, host, http.MethodGet, chainPath, nil)
	if !ok {
		return false
	}
	var theirRoot string
	for _, r := range theirs.Records {
		if r.Kind == kindGenesis {
			theirRoot = r.hash()
			break
		}
	}
	if mine := n.chain.rootHash(); mine != "" && theirRoot != "" && mine != theirRoot {
		n.chain.clear()
	}
	n.chain.ingest(theirs.Records)
	n.applier.acknowledgeHistory(n.chain.view())
	n.applier.pairedInPerson(hostID, n.chain.view().State.Seq)
	n.notePairedInPerson()
	n.saveChain()
	n.applyChainNow()
	n.takeProfiles(theirs.Profiles)
	return true
}

// tickChain is what this node does for the chain each minute and after a pairing: trade records with its peers, say
// so when its TLS key changed, and, when it is not an active member under a key of its own, ask an active member.
func (n *Node) tickChain(ctx context.Context) {
	n.tickMu.Lock()
	defer n.tickMu.Unlock()
	if !n.isJoined() {
		return
	}
	var ids []string
	for _, peer := range n.roster() {
		if peer.LastKnownIP == "" || peer.Port <= 0 {
			continue
		}
		ids = append(ids, peer.DeviceID)
		n.reconcile(ctx, peer)
		if !n.isJoined() {
			return
		}
	}
	state := n.chain.view().State
	n.announceOwnPin(ctx, state)
	if state.Seq == 0 {
		return
	}
	self := n.deviceID()
	_, key := n.signer()
	me, has := state.Members[self]
	if has && me.Active {
		if isKeylessKey(me.Key) && me.Key != key {
			n.askToJoin(ctx, ids, state, key)
		}
		return
	}
	if has && me.Key == key {
		if !n.pairedRecently() {
			return
		}
		n.mu.Lock()
		n.pairedAt = time.Time{}
		n.mu.Unlock()
		n.saveChain()
		log.Printf("Removed under its current key and paired in person: taking a new signing key.")
		n.renewSigning()
		_, key = n.signer()
		if me.Key == key {
			return
		}
	}
	n.askToJoin(ctx, ids, state, key)
}

// askToJoin asks the active members that hold a key of their own, one after another, until the chain has this node with key.
func (n *Node) askToJoin(ctx context.Context, ids []string, state chainState, key string) {
	self := n.deviceID()
	for _, id := range ids {
		member, ok := state.Members[id]
		if !ok || !member.Active || isKeylessKey(member.Key) {
			continue
		}
		peer, ok := n.rosterPeer(id)
		if !ok {
			continue
		}
		own := n.selfState()
		answer, ok := n.chainCall(ctx, peer, http.MethodPost, chainPath+"/join", chainJoinRequest{Key: key, TLSPin: n.tlsPinValue(), Profile: &own})
		if !ok {
			continue
		}
		n.chain.ingest(answer.Records)
		n.saveChain()
		n.applyChainNow()
		n.takeProfiles(answer.Profiles)
		if m, ok := n.chain.view().State.Members[self]; ok && m.Active && m.Key == key {
			return
		}
	}
}

// announceOwnPin: this node's TLS key changed since the chain last heard of it, so the new pin goes into a record signed with the signing key.
func (n *Node) announceOwnPin(ctx context.Context, state chainState) {
	me, ok := state.Members[n.deviceID()]
	pin := n.tlsPinValue()
	if !ok || !me.Active || pin == "" || me.TLSPin == pin {
		return
	}
	signing, _ := n.signer()
	if _, err := n.chain.append(signing, n.deviceID(), kindTLSPin, n.deviceID(), me.Key, pin, reasonTLSPin); err != nil {
		log.Printf("Could not record the new TLS key: %v", err)
		return
	}
	n.saveChain()
	n.announceChain(ctx)
}

// leaveCluster: the user resets this node. A LEAVE goes to the other members first, then everything of the cluster is let go.
func (n *Node) leaveCluster(ctx context.Context) {
	state := n.chain.view().State
	self := n.deviceID()
	if me, ok := state.Members[self]; ok && me.Active {
		signing, _ := n.signer()
		if _, err := n.chain.append(signing, self, kindLeave, self, me.Key, me.TLSPin, reasonLeft); err == nil {
			n.saveChain()
			n.announceChain(ctx)
		}
	}
	n.wipeCluster()
}
