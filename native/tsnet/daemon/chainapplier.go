package main

import (
	"sync"
	"time"
)

const pairingGrace = 10 * time.Minute

// chainEffects is what the chain changes on this node, behind an interface so the rules test without a network.
type chainEffects interface {
	knownPin(id string) (string, bool)
	setPin(id, pin string)
	// ensureProfile gives an active member a roster entry, a stub until a profile fills it in.
	ensureProfile(id string)
	// selfRemoved: this node was removed by `by` in the record `hash`; it lets go of its cluster.
	selfRemoved(by, hash string)
	handledRemoval() string
	markHandled(hash string)
	// knownRemoved is true when this node already let `id` go.
	knownRemoved(id string) bool
	// markRemoved: the chain removed or released `id`; `pin` is the key the record names, kept as revoked so the device can still read its removal.
	markRemoved(id, by, reason, pin string)
}

type pairingMark struct {
	at       time.Time
	chainSeq int64
}

// chainApplier applies the walked chain to this node: the same rules as the Kotlin ChainApplier.
type chainApplier struct {
	selfID string
	fx     chainEffects
	now    func() time.Time
	mu     sync.Mutex
	grace  map[string]pairingMark
}

func newChainApplier(selfID string, fx chainEffects, now func() time.Time) *chainApplier {
	return &chainApplier{selfID: selfID, fx: fx, now: now, grace: map[string]pairingMark{}}
}

// pairedInPerson: the user just paired `id` in person when the chain was at `chainSeq`. For a short while a removal of
// it that was already in the chain is not applied, so it can join first.
func (a *chainApplier) pairedInPerson(id string, chainSeq int64) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.grace[id] = pairingMark{at: a.now(), chainSeq: chainSeq}
}

// heldRemoval is a hold as it is kept on disk.
type heldRemoval struct {
	AtMs     int64 `json:"atMs"`
	ChainSeq int64 `json:"chainSeq"`
}

// snapshot is the holds that can still matter, for the file the node keeps.
func (a *chainApplier) snapshot() map[string]heldRemoval {
	a.mu.Lock()
	defer a.mu.Unlock()
	out := map[string]heldRemoval{}
	for id, mark := range a.grace {
		if a.now().Sub(mark.at) <= pairingGrace {
			out[id] = heldRemoval{AtMs: mark.at.UnixMilli(), ChainSeq: mark.chainSeq}
		}
	}
	return out
}

// restore puts back the holds a restart would otherwise lose.
func (a *chainApplier) restore(holds map[string]heldRemoval) {
	a.mu.Lock()
	defer a.mu.Unlock()
	for id, hold := range holds {
		a.grace[id] = pairingMark{at: time.UnixMilli(hold.AtMs), chainSeq: hold.ChainSeq}
	}
}

func (a *chainApplier) heldBack(id string, removalSeq int64) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	mark, ok := a.grace[id]
	return ok && a.now().Sub(mark.at) <= pairingGrace && removalSeq <= mark.chainSeq
}

func lastRemovalOf(view chainView, id string, kinds ...recordKind) (chainRecord, bool) {
	for i := len(view.Canonical) - 1; i >= 0; i-- {
		r := view.Canonical[i]
		if r.Subject != id {
			continue
		}
		for _, kind := range kinds {
			if r.Kind == kind {
				return r, true
			}
		}
	}
	return chainRecord{}, false
}

// acknowledgeHistory: a removal of this node that is already in the chain it just took in person is history, not a kick.
func (a *chainApplier) acknowledgeHistory(view chainView) {
	me, ok := view.State.Members[a.selfID]
	if !ok || me.Active {
		return
	}
	if removal, ok := lastRemovalOf(view, a.selfID, kindRemove); ok {
		a.fx.markHandled(removal.hash())
	}
}

func (a *chainApplier) apply(view chainView) {
	for id, member := range view.State.Members {
		if id == a.selfID || !member.Active || member.TLSPin == "" {
			continue
		}
		known, have := a.fx.knownPin(id)
		if known != member.TLSPin && (!have || !isKeylessKey(member.Key)) {
			a.fx.setPin(id, member.TLSPin)
		}
		a.fx.ensureProfile(id)
	}
	for id, member := range view.State.Members {
		if id == a.selfID || member.Active || a.fx.knownRemoved(id) {
			continue
		}
		record, ok := lastRemovalOf(view, id, kindRemove, kindLeave)
		if !ok || a.heldBack(id, record.Seq) {
			continue
		}
		a.fx.markRemoved(id, record.Actor, record.Reason, record.TLSPin)
	}
	me, ok := view.State.Members[a.selfID]
	if !ok || me.Active {
		return
	}
	removal, ok := lastRemovalOf(view, a.selfID, kindRemove)
	if !ok {
		return
	}
	hash := removal.hash()
	if a.fx.handledRemoval() == hash {
		return
	}
	a.fx.markHandled(hash)
	a.fx.selfRemoved(removal.Actor, hash)
}
