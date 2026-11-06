package main

import (
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

// chainStateHeader carries "v1.<seq>.<head32>.<profile8>": how far this node's chain goes, the first 128 bits of its
// head, and a digest of its own stable profile. A proven peer reads it on a health answer and shows its own the same way
// with the cs query. It only decides whether to ask; records are still verified by signature.
const chainStateHeader = "X-FileApex-Chain-State"
const chainStateMaxLength = 128
const chainStateHeadChars = 32

var chainStatePattern = regexp.MustCompile(`^v1\.([0-9]{1,9})\.([0-9a-f]{0,32})\.([0-9a-f]{8})$`)

type chainStateField struct {
	Seq     int64
	Head    string
	Profile string
}

func newChainStateField(seq int64, head, profile string) chainStateField {
	if len(head) > chainStateHeadChars {
		head = head[:chainStateHeadChars]
	}
	return chainStateField{Seq: seq, Head: head, Profile: profile}
}

func (f chainStateField) encode() string {
	return fmt.Sprintf("v1.%d.%s.%s", f.Seq, f.Head, f.Profile)
}

// parseChainState is the field in raw, or false for anything that is not exactly one well-formed value.
func parseChainState(raw string) (chainStateField, bool) {
	if len(raw) > chainStateMaxLength {
		return chainStateField{}, false
	}
	m := chainStatePattern.FindStringSubmatch(raw)
	if m == nil {
		return chainStateField{}, false
	}
	seq, err := strconv.ParseInt(m[1], 10, 64)
	if err != nil || (seq == 0) != (m[2] == "") || (m[2] != "" && len(m[2]) != chainStateHeadChars) {
		return chainStateField{}, false
	}
	return chainStateField{Seq: seq, Head: m[2], Profile: m[3]}, true
}

// profileDigest is eight hex characters over the fields of a node's own profile that change rarely. Not the address and
// nothing relayed, so a node that moves does not change it; a new version or name does.
func profileDigest(s nodeState) string {
	h := sha256.New()
	for _, part := range []string{
		s.DeviceID, s.DeviceName, strconv.Itoa(s.Port), s.ClientVersion, s.AppVersion, strconv.Itoa(s.ClientVersionCode),
		strconv.Itoa(s.AppVersionCode), s.Platform, s.OS, s.DeviceMake, s.DeviceModel, strings.Join(s.SupportedProtocols, ","),
		s.RootPath, s.PublicKeyHash, s.PublicKey, strconv.FormatBool(s.PinRequired), s.TLSPin, strconv.Itoa(s.TLSPort),
	} {
		var size [4]byte
		binary.BigEndian.PutUint32(size[:], uint32(len(part)))
		h.Write(size[:])
		h.Write([]byte(part))
	}
	return hex.EncodeToString(h.Sum(nil)[:4])
}

func (n *Node) ownChainState() chainStateField {
	state := n.chain.view().State
	return newChainStateField(state.Seq, state.Head, profileDigest(n.selfState()))
}

const (
	watchMinGap      = 10 * time.Second
	watchFlapLimit   = 6
	watchMaxPulls    = 2
	watchMaxAttempts = 8
	watchMaxPeers    = 128
)

var watchBackoff = []time.Duration{time.Minute, 5 * time.Minute, 30 * time.Minute, time.Hour}

type watchAttempt struct {
	count  int
	nextAt time.Time
}

type watchPeer struct {
	lastPullAt time.Time
	field      *chainStateField
	attempts   map[string]*watchAttempt
	changes    []time.Time
	flapping   bool
}

// chainWatch decides, from the state a proven peer shows, whether this node needs to ask that peer for anything. Equal
// state costs nothing; a peer ahead or on another branch is pulled once per distinct head and then on a back-off
// table. This node never probes a peer's health, so its own tick keeps asking every peer once a minute (an answer that
// says nothing is new is a few hundred bytes): that is the only way it hears of a removal whose push was missed.
type chainWatch struct {
	mu    sync.Mutex
	own   func() chainStateField
	pull  func(id string) bool
	now   func() time.Time
	peers map[string]*watchPeer
	slots chan struct{}
}

func newChainWatch(own func() chainStateField, pull func(id string) bool, now func() time.Time) *chainWatch {
	return &chainWatch{own: own, pull: pull, now: now, peers: map[string]*watchPeer{}, slots: make(chan struct{}, watchMaxPulls)}
}

func (w *chainWatch) peerLocked(id string) *watchPeer {
	p, ok := w.peers[id]
	if !ok {
		if len(w.peers) >= watchMaxPeers {
			for old := range w.peers {
				delete(w.peers, old)
				break
			}
		}
		p = &watchPeer{attempts: map[string]*watchAttempt{}}
		w.peers[id] = p
	}
	return p
}

func (w *chainWatch) fieldOf(id string) (chainStateField, bool) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if p, ok := w.peers[id]; ok && p.field != nil {
		return *p.field, true
	}
	return chainStateField{}, false
}

// observe keeps what a peer showed at once and looks at it in the background, so an answer never waits for a pull.
func (w *chainWatch) observe(id string, theirs chainStateField) {
	w.record(id, theirs)
	go w.consider(id, theirs)
}

// note is record and consider in one step.
func (w *chainWatch) note(id string, theirs chainStateField) {
	w.record(id, theirs)
	w.consider(id, theirs)
}

func (w *chainWatch) record(id string, theirs chainStateField) {
	t := w.now()
	w.mu.Lock()
	defer w.mu.Unlock()
	p := w.peerLocked(id)
	if p.field != nil && *p.field != theirs {
		p.changes = append(p.changes, t)
		for len(p.changes) > 0 && t.Sub(p.changes[0]) > time.Hour {
			p.changes = p.changes[1:]
		}
		if len(p.changes) > watchFlapLimit {
			p.flapping = true
		}
	}
	p.field = &theirs
}

func (w *chainWatch) consider(id string, theirs chainStateField) {
	mine := w.own()
	t := w.now()
	head, ok := w.decide(id, theirs, mine, t)
	if !ok {
		return
	}
	defer func() { <-w.slots }()
	if w.pull(id) {
		if after := w.own(); after.Seq == theirs.Seq && after.Head == theirs.Head {
			w.mu.Lock()
			delete(w.peerLocked(id).attempts, head)
			w.mu.Unlock()
		}
	}
}

// decide returns the head to pull and takes a pull slot, or false for nothing to do.
func (w *chainWatch) decide(id string, theirs, mine chainStateField, t time.Time) (string, bool) {
	w.mu.Lock()
	defer w.mu.Unlock()
	p := w.peerLocked(id)
	same := theirs.Seq == mine.Seq && theirs.Head == mine.Head
	if theirs.Seq == 0 || same || theirs.Seq < mine.Seq || t.Sub(p.lastPullAt) < watchMinGap {
		return "", false
	}
	a := p.attempts[theirs.Head]
	if a != nil && t.Before(a.nextAt) {
		return "", false
	}
	if a == nil && p.flapping && t.Before(p.lastPullAt.Add(watchBackoff[0])) {
		return "", false
	}
	select {
	case w.slots <- struct{}{}:
	default:
		return "", false
	}
	p.lastPullAt = t
	if a == nil {
		if len(p.attempts) >= watchMaxAttempts {
			for old := range p.attempts {
				delete(p.attempts, old)
				break
			}
		}
		a = &watchAttempt{}
		p.attempts[theirs.Head] = a
	}
	a.count++
	a.nextAt = t.Add(watchBackoff[min(a.count, len(watchBackoff))-1])
	return theirs.Head, true
}
