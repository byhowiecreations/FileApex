package main

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
)

type recordKind string

const (
	kindGenesis recordKind = "GENESIS"
	kindAdmit   recordKind = "ADMIT"
	kindRemove  recordKind = "REMOVE"
	kindLeave   recordKind = "LEAVE"
	kindTLSPin  recordKind = "TLS_PIN"
)

const (
	reasonPaired      = "paired"
	reasonUserRemoved = "user_removed"
	reasonLeft        = "left_cluster"
	reasonTLSPin      = "tls_pin"
	legacyKeyPrefix   = "legacy:"
)

type chainRecord struct {
	Seq        int64      `json:"seq"`
	ParentHash string     `json:"parentHash"`
	Kind       recordKind `json:"kind"`
	Actor      string     `json:"actor"`
	ActorKey   string     `json:"actorKey"`
	Subject    string     `json:"subject"`
	SubjectKey string     `json:"subjectKey"`
	TLSPin     string     `json:"tlsPin"`
	Reason     string     `json:"reason"`
	Signature  string     `json:"signature"`
}

type chainMember struct {
	Key    string `json:"key"`
	TLSPin string `json:"tlsPin"`
	Active bool   `json:"active"`
}

type chainState struct {
	Seq     int64
	Head    string
	Members map[string]chainMember
}

type chainView struct {
	State     chainState
	Canonical []chainRecord
}

type chainLog struct {
	mu      sync.Mutex
	records []chainRecord
	selfID  func() string
}

const chainDomain = "fileapex-membership-chain-v1"

var chainPinPattern = regexp.MustCompile("^[0-9a-f]{64}$")

// canonicalBytes is what the signature covers: every field length-prefixed, so no two records share bytes.
func (r chainRecord) canonicalBytes() []byte {
	var out bytes.Buffer
	put := func(text string) {
		var size [4]byte
		binary.BigEndian.PutUint32(size[:], uint32(len(text)))
		out.Write(size[:])
		out.WriteString(text)
	}
	put(chainDomain)
	put(strconv.FormatInt(r.Seq, 10))
	for _, field := range []string{r.ParentHash, string(r.Kind), r.Actor, r.ActorKey, r.Subject, r.SubjectKey, r.TLSPin, r.Reason} {
		put(field)
	}
	return out.Bytes()
}

// hash is the id of a record and the parent hash of the next: SHA-256 over the signed bytes and the signature.
func (r chainRecord) hash() string {
	sum := sha256.New()
	sum.Write(r.canonicalBytes())
	sum.Write([]byte(r.Signature))
	return hex.EncodeToString(sum.Sum(nil))
}

func decodeSPKI(b64 string) (*ecdsa.PublicKey, error) {
	der, err := base64.StdEncoding.DecodeString(b64)
	if err != nil {
		return nil, err
	}
	parsed, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		return nil, err
	}
	pub, ok := parsed.(*ecdsa.PublicKey)
	if !ok {
		return nil, errors.New("not an EC key")
	}
	return pub, nil
}

func spkiBase64(pub *ecdsa.PublicKey) string {
	der, err := x509.MarshalPKIXPublicKey(pub)
	if err != nil {
		return ""
	}
	return base64.StdEncoding.EncodeToString(der)
}

// verifiedLimit bounds the set of records whose signature is remembered as good. A chain is a few dozen records; the
// bound only stops a flood of valid records from growing the set.
const verifiedLimit = 4096

var (
	verifiedMu     sync.Mutex
	verifiedSet    = map[string]struct{}{}
	signatureCount atomic.Int64
)

// verifyRecord is true when the record's signature holds. A good answer is remembered by the record's hash, which covers
// every signed byte and the signature, so a record is checked once however often the chain is read. A bad one is never remembered.
func verifyRecord(r chainRecord) bool {
	if r.Signature == "" {
		return false
	}
	id := r.hash()
	verifiedMu.Lock()
	_, known := verifiedSet[id]
	verifiedMu.Unlock()
	if known {
		return true
	}
	good := signatureHolds(r)
	if good {
		verifiedMu.Lock()
		if len(verifiedSet) >= verifiedLimit {
			for old := range verifiedSet {
				delete(verifiedSet, old)
				break
			}
		}
		verifiedSet[id] = struct{}{}
		verifiedMu.Unlock()
	}
	return good
}

func signatureChecks() int64 { return signatureCount.Load() }

func verifiedSize() int {
	verifiedMu.Lock()
	defer verifiedMu.Unlock()
	return len(verifiedSet)
}

func resetVerifyCache() {
	verifiedMu.Lock()
	verifiedSet = map[string]struct{}{}
	verifiedMu.Unlock()
	signatureCount.Store(0)
}

func signatureHolds(r chainRecord) bool {
	signatureCount.Add(1)
	pub, err := decodeSPKI(r.ActorKey)
	if err != nil {
		return false
	}
	sig, err := base64.StdEncoding.DecodeString(r.Signature)
	if err != nil {
		return false
	}
	digest := sha256.Sum256(r.canonicalBytes())
	return ecdsa.VerifyASN1(pub, digest[:], sig)
}

func signRecord(key *ecdsa.PrivateKey, unsigned chainRecord) chainRecord {
	digest := sha256.Sum256(unsigned.canonicalBytes())
	sig, err := ecdsa.SignASN1(rand.Reader, key, digest[:])
	if err != nil {
		return unsigned
	}
	unsigned.Signature = base64.StdEncoding.EncodeToString(sig)
	return unsigned
}

// isKeylessKey is true for a member admitted for a device that never ran the chain: it has no key of its own.
func isKeylessKey(key string) bool {
	return strings.TrimSpace(key) == "" || strings.HasPrefix(key, legacyKeyPrefix)
}

func pinOK(pin string) bool { return pin == "" || chainPinPattern.MatchString(pin) }

func (s chainState) activeIDs() []string {
	var ids []string
	for id, m := range s.Members {
		if m.Active {
			ids = append(ids, id)
		}
	}
	sort.Strings(ids)
	return ids
}

// applyRecord checks r against the state just before it: signature first, then its place in the chain, then the rules of its kind.
func applyRecord(state chainState, r chainRecord) (chainState, error) {
	if !verifyRecord(r) {
		return state, errors.New("bad signature")
	}
	if r.Seq != state.Seq+1 || r.ParentHash != state.Head {
		return state, errors.New("not the next record of this chain")
	}
	if r.Kind == kindGenesis {
		if state.Seq != 0 || r.Actor != r.Subject || r.ActorKey != r.SubjectKey {
			return state, errors.New("bad genesis")
		}
		if !pinOK(r.TLSPin) {
			return state, errors.New("bad pin")
		}
		return chainState{Seq: 1, Head: r.hash(), Members: map[string]chainMember{r.Subject: {Key: r.SubjectKey, TLSPin: r.TLSPin, Active: true}}}, nil
	}
	actor, ok := state.Members[r.Actor]
	if !ok || !actor.Active || actor.Key != r.ActorKey {
		return state, errors.New("the actor is not an active member with this key")
	}
	next := make(map[string]chainMember, len(state.Members)+1)
	for id, m := range state.Members {
		next[id] = m
	}
	switch r.Kind {
	case kindAdmit:
		known, exists := state.Members[r.Subject]
		if r.Subject == r.Actor || strings.TrimSpace(r.SubjectKey) == "" {
			return state, errors.New("nothing to admit")
		}
		keyless := strings.HasPrefix(r.SubjectKey, legacyKeyPrefix)
		if exists && known.Active {
			if !isKeylessKey(known.Key) || keyless {
				return state, errors.New("already a member")
			}
		} else if exists {
			if known.Key == r.SubjectKey {
				return state, errors.New("a removed device needs a new key")
			}
			if keyless {
				return state, errors.New("a removed device needs a real new key")
			}
		}
		if !pinOK(r.TLSPin) {
			return state, errors.New("bad pin")
		}
		next[r.Subject] = chainMember{Key: r.SubjectKey, TLSPin: r.TLSPin, Active: true}
	case kindRemove:
		target, exists := state.Members[r.Subject]
		if !exists || !target.Active || r.Subject == r.Actor {
			return state, errors.New("no active member to remove")
		}
		if r.Reason != reasonUserRemoved {
			return state, errors.New("a removal says user_removed")
		}
		target.Active = false
		next[r.Subject] = target
	case kindLeave:
		if r.Subject != r.Actor || r.Reason != reasonLeft {
			return state, errors.New("a device leaves only itself")
		}
		actor.Active = false
		next[r.Subject] = actor
	case kindTLSPin:
		if r.Subject != r.Actor || !chainPinPattern.MatchString(r.TLSPin) {
			return state, errors.New("a member announces only its own pin")
		}
		actor.TLSPin = r.TLSPin
		next[r.Subject] = actor
	default:
		return state, errors.New("unknown record")
	}
	anyActive := false
	for _, m := range next {
		if m.Active {
			anyActive = true
			break
		}
	}
	if !anyActive {
		return state, errors.New("the last active member stays")
	}
	return chainState{Seq: r.Seq, Head: r.hash(), Members: next}, nil
}

// preferredRecord picks one of two records with the same parent: a removal beats a record that is not one, otherwise the lower hash wins.
func preferredRecord(a, b chainRecord) chainRecord {
	aRemoves, bRemoves := a.Kind == kindRemove, b.Kind == kindRemove
	switch {
	case aRemoves != bRemoves:
		if aRemoves {
			return a
		}
		return b
	case a.hash() <= b.hash():
		return a
	default:
		return b
	}
}

func newChainLog(selfID func() string) *chainLog {
	if selfID == nil {
		selfID = func() string { return "" }
	}
	return &chainLog{selfID: selfID}
}

func (l *chainLog) holdsLocked(hash string) bool {
	for _, r := range l.records {
		if r.hash() == hash {
			return true
		}
	}
	return false
}

// rawPut stores a record without any check; ingest and append are the ways in.
func (l *chainLog) rawPut(r chainRecord) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.putLocked(r)
}

func (l *chainLog) putLocked(r chainRecord) bool {
	if l.holdsLocked(r.hash()) {
		return false
	}
	l.records = append(l.records, r)
	return true
}

// rootLocked is the one genesis this device follows: the one it wrote, otherwise the lower hash.
func (l *chainLog) rootLocked() (chainRecord, bool) {
	var best chainRecord
	found := false
	self := l.selfID()
	for _, r := range l.records {
		if r.Kind != kindGenesis || r.ParentHash != "" {
			continue
		}
		if self != "" && r.Actor == self {
			return r, true
		}
		if !found || r.hash() < best.hash() {
			best, found = r, true
		}
	}
	return best, found
}

func (l *chainLog) rootHash() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	if root, ok := l.rootLocked(); ok {
		return root.hash()
	}
	return ""
}

func (l *chainLog) viewLocked() chainView {
	byParent := map[string][]chainRecord{}
	for _, r := range l.records {
		byParent[r.ParentHash] = append(byParent[r.ParentHash], r)
	}
	state := chainState{Members: map[string]chainMember{}}
	var canonical []chainRecord
	root, hasRoot := l.rootLocked()
	for {
		var candidates []chainRecord
		if state.Head == "" {
			if hasRoot {
				candidates = []chainRecord{root}
			}
		} else {
			candidates = byParent[state.Head]
		}
		var pick chainRecord
		var picked chainState
		found := false
		for _, c := range candidates {
			after, err := applyRecord(state, c)
			if err != nil {
				continue
			}
			if !found || preferredRecord(c, pick).hash() == c.hash() {
				pick, picked, found = c, after, true
			}
		}
		if !found {
			break
		}
		canonical = append(canonical, pick)
		state = picked
	}
	return chainView{State: state, Canonical: canonical}
}

func (l *chainLog) view() chainView {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.viewLocked()
}

// ingest stores records from another device whose signature holds. A second genesis is another cluster and is never
// stored. Whether a record belongs to the chain is decided when it is walked, so one that arrives before its parent waits.
func (l *chainLog) ingest(records []chainRecord) int {
	l.mu.Lock()
	defer l.mu.Unlock()
	added := 0
	for _, r := range records {
		if !verifyRecord(r) {
			continue
		}
		if r.Kind == kindGenesis && l.hasGenesisLocked() {
			continue
		}
		if l.putLocked(r) {
			added++
		}
	}
	return added
}

func (l *chainLog) hasGenesisLocked() bool {
	for _, r := range l.records {
		if r.Kind == kindGenesis {
			return true
		}
	}
	return false
}

// recordsAfter is every stored record of this device's cluster above seq: losing fork branches included so a fork
// resolves everywhere, another cluster's records not.
func (l *chainLog) recordsAfter(seq int64) []chainRecord {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.recordsAfterLocked(seq)
}

func (l *chainLog) recordsAfterLocked(seq int64) []chainRecord {
	byParent := map[string][]chainRecord{}
	for _, r := range l.records {
		byParent[r.ParentHash] = append(byParent[r.ParentHash], r)
	}
	var under []chainRecord
	root, ok := l.rootLocked()
	if !ok {
		return nil
	}
	queue := []chainRecord{root}
	for len(queue) > 0 {
		r := queue[0]
		queue = queue[1:]
		under = append(under, r)
		queue = append(queue, byParent[r.hash()]...)
	}
	var out []chainRecord
	for _, r := range under {
		if r.Seq > seq {
			out = append(out, r)
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Seq < out[j].Seq })
	return out
}

// recordsAfterHead is what came after the record whose hash is head, for a device that holds the chain up to it. It is
// false when the asker's position cannot be told (an empty or unknown head, or one on a losing branch) and the caller
// sends everything.
func (l *chainLog) recordsAfterHead(head string) ([]chainRecord, bool) {
	if head == "" {
		return nil, false
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	for _, r := range l.viewLocked().Canonical {
		if r.hash() == head {
			return l.recordsAfterLocked(r.Seq), true
		}
	}
	return nil, false
}

func (l *chainLog) clear() {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.records = nil
}

// append builds, signs and stores a record on top of the current chain. A rejected record is not stored.
func (l *chainLog) append(key *ecdsa.PrivateKey, actorID string, kind recordKind, subject, subjectKey, pin, reason string) (chainRecord, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	state := l.viewLocked().State
	record := signRecord(key, chainRecord{
		Seq: state.Seq + 1, ParentHash: state.Head, Kind: kind, Actor: actorID, ActorKey: spkiBase64(&key.PublicKey),
		Subject: subject, SubjectKey: subjectKey, TLSPin: pin, Reason: reason,
	})
	if _, err := applyRecord(state, record); err != nil {
		return chainRecord{}, err
	}
	l.putLocked(record)
	return record, nil
}
