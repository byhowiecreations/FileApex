package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"encoding/json"
	"os"
	"sort"
	"strings"
	"testing"
)

type tdev struct {
	id   string
	priv *ecdsa.PrivateKey
	key  string
}

func newTDev(t *testing.T, id string) tdev {
	t.Helper()
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return tdev{id: id, priv: priv, key: spkiBase64(&priv.PublicKey)}
}

func pinOf(c byte) string { return strings.Repeat(string(c), 64) }

func (d tdev) record(state chainState, kind recordKind, subject, subjectKey, pin, reason string) chainRecord {
	return signRecord(d.priv, chainRecord{
		Seq: state.Seq + 1, ParentHash: state.Head, Kind: kind, Actor: d.id, ActorKey: d.key,
		Subject: subject, SubjectKey: subjectKey, TLSPin: pin, Reason: reason,
	})
}

func mustApply(t *testing.T, state chainState, r chainRecord) chainState {
	t.Helper()
	next, err := applyRecord(state, r)
	if err != nil {
		t.Fatalf("record %s %d refused: %v", r.Kind, r.Seq, err)
	}
	return next
}

func mustRefuse(t *testing.T, state chainState, r chainRecord, why string) {
	t.Helper()
	if _, err := applyRecord(state, r); err == nil {
		t.Fatalf("%s: the record was accepted", why)
	}
}

func startChain(t *testing.T, a tdev) chainState {
	t.Helper()
	return mustApply(t, chainState{}, a.record(chainState{}, kindGenesis, a.id, a.key, pinOf('a'), reasonPaired))
}

func twoMembers(t *testing.T, a, b tdev) chainState {
	s := startChain(t, a)
	return mustApply(t, s, a.record(s, kindAdmit, b.id, b.key, pinOf('b'), reasonPaired))
}

func TestARecordHashCoversItsSignatureAndAForgeryIsRefused(t *testing.T) {
	a := newTDev(t, "a")
	r := a.record(chainState{}, kindGenesis, a.id, a.key, pinOf('a'), reasonPaired)
	if !verifyRecord(r) || r.hash() == "" || len(r.hash()) != 64 {
		t.Fatalf("a signed record must verify and have a hex hash, got %q", r.hash())
	}
	forged := r
	forged.Reason = "something else"
	if verifyRecord(forged) {
		t.Fatal("a changed record verified")
	}
	resigned := r
	resigned.Signature = "AAAA"
	if verifyRecord(resigned) || resigned.hash() == r.hash() {
		t.Fatal("a different signature must not verify and must change the hash")
	}
	if verifyRecord(chainRecord{}) {
		t.Fatal("an unsigned record verified")
	}
}

func TestAGenesisCreatesOneActiveMemberAndAnythingElseIsRefused(t *testing.T) {
	a, b := newTDev(t, "a"), newTDev(t, "b")
	s := startChain(t, a)
	if s.Seq != 1 || len(s.Members) != 1 || !s.Members["a"].Active {
		t.Fatalf("unexpected state %+v", s)
	}
	mustRefuse(t, chainState{}, a.record(chainState{}, kindGenesis, b.id, b.key, pinOf('a'), reasonPaired), "a genesis for another device")
	mustRefuse(t, s, a.record(chainState{}, kindGenesis, a.id, a.key, pinOf('a'), reasonPaired), "a second genesis on a chain")
	mustRefuse(t, chainState{}, a.record(chainState{}, kindGenesis, a.id, a.key, "xyz", reasonPaired), "a bad pin")
}

func TestPlaceInTheChainIsChecked(t *testing.T) {
	a, b, c := newTDev(t, "a"), newTDev(t, "b"), newTDev(t, "c")
	s := twoMembers(t, a, b)
	wrongParent := a.record(s, kindAdmit, c.id, c.key, pinOf('c'), reasonPaired)
	wrongParent.ParentHash = "ff"
	mustRefuse(t, s, signRecord(a.priv, wrongParent), "a wrong parent")
	gap := a.record(s, kindAdmit, c.id, c.key, pinOf('c'), reasonPaired)
	gap.Seq += 1
	mustRefuse(t, s, signRecord(a.priv, gap), "a gap in the sequence")
	old := a.record(chainState{}, kindGenesis, a.id, a.key, pinOf('a'), reasonPaired)
	mustRefuse(t, s, old, "a replayed record")
}

func TestOnlyAnActiveMemberWithTheRightKeyWritesAndTheLastOneStays(t *testing.T) {
	a, b, c := newTDev(t, "a"), newTDev(t, "b"), newTDev(t, "c")
	s := twoMembers(t, a, b)
	mustRefuse(t, s, c.record(s, kindAdmit, "d", c.key, pinOf('d'), reasonPaired), "an outsider writing")
	liar := a.record(s, kindAdmit, c.id, c.key, pinOf('c'), reasonPaired)
	liar.ActorKey = b.key
	mustRefuse(t, s, signRecord(b.priv, liar), "an actor key that is not the member's")
	removed := mustApply(t, s, a.record(s, kindRemove, b.id, b.key, pinOf('b'), reasonUserRemoved))
	mustRefuse(t, removed, b.record(removed, kindAdmit, c.id, c.key, pinOf('c'), reasonPaired), "a removed member writing")
	mustRefuse(t, removed, a.record(removed, kindRemove, a.id, a.key, pinOf('a'), reasonUserRemoved), "removing oneself")
	mustRefuse(t, removed, a.record(removed, kindLeave, a.id, a.key, pinOf('a'), reasonLeft), "the last active member leaving")
}

func TestLeavePinAndRemoveCarryTheirOwnRules(t *testing.T) {
	a, b := newTDev(t, "a"), newTDev(t, "b")
	s := twoMembers(t, a, b)
	mustRefuse(t, s, a.record(s, kindLeave, b.id, b.key, pinOf('b'), reasonLeft), "leaving for someone else")
	mustRefuse(t, s, b.record(s, kindLeave, b.id, b.key, pinOf('b'), "other"), "a leave with the wrong reason")
	mustRefuse(t, s, a.record(s, kindRemove, b.id, b.key, pinOf('b'), "other"), "a removal with the wrong reason")
	mustRefuse(t, s, a.record(s, kindTLSPin, b.id, b.key, pinOf('2'), reasonTLSPin), "announcing someone else's pin")
	mustRefuse(t, s, b.record(s, kindTLSPin, b.id, b.key, "short", reasonTLSPin), "a malformed pin")
	next := mustApply(t, s, b.record(s, kindTLSPin, b.id, b.key, pinOf('2'), reasonTLSPin))
	if next.Members["b"].TLSPin != pinOf('2') {
		t.Fatal("the announced pin was not taken")
	}
	left := mustApply(t, s, b.record(s, kindLeave, b.id, b.key, pinOf('b'), reasonLeft))
	if left.Members["b"].Active {
		t.Fatal("a member that left is still active")
	}
}

func TestARemovedDeviceNeedsARealNewKey(t *testing.T) {
	a, b := newTDev(t, "a"), newTDev(t, "b")
	s := twoMembers(t, a, b)
	removed := mustApply(t, s, a.record(s, kindRemove, b.id, b.key, pinOf('b'), reasonUserRemoved))
	mustRefuse(t, removed, a.record(removed, kindAdmit, b.id, b.key, pinOf('b'), reasonPaired), "the same key again")
	mustRefuse(t, removed, a.record(removed, kindAdmit, b.id, legacyKeyPrefix+b.id, pinOf('b'), reasonPaired), "a key-less re-admit")
	fresh := newTDev(t, "b")
	back := mustApply(t, removed, a.record(removed, kindAdmit, b.id, fresh.key, pinOf('2'), reasonPaired))
	if !back.Members["b"].Active || back.Members["b"].Key != fresh.key {
		t.Fatalf("the device was not admitted with its new key: %+v", back.Members["b"])
	}
}

func TestAKeylessMemberIsBoundToItsRealKeyAndNothingElseIsRebound(t *testing.T) {
	a, b, c := newTDev(t, "a"), newTDev(t, "b"), newTDev(t, "c")
	s := twoMembers(t, a, b)
	s = mustApply(t, s, a.record(s, kindAdmit, c.id, legacyKeyPrefix+c.id, pinOf('c'), reasonPaired))
	mustRefuse(t, s, a.record(s, kindAdmit, c.id, legacyKeyPrefix+c.id, pinOf('c'), reasonPaired), "a key-less member bound to a key-less key")
	fresh := newTDev(t, "b")
	mustRefuse(t, s, a.record(s, kindAdmit, b.id, fresh.key, pinOf('2'), reasonPaired), "a member that has a key being rebound")
	bound := mustApply(t, s, a.record(s, kindAdmit, c.id, c.key, pinOf('c'), reasonPaired))
	if bound.Members["c"].Key != c.key || !bound.Members["c"].Active {
		t.Fatalf("the key was not bound: %+v", bound.Members["c"])
	}
}

func TestAForkResolvesTheSameWayInEitherOrder(t *testing.T) {
	a, b, e := newTDev(t, "a"), newTDev(t, "b"), newTDev(t, "e")
	s := twoMembers(t, a, b)
	remove := a.record(s, kindRemove, b.id, b.key, pinOf('b'), reasonUserRemoved)
	admit := a.record(s, kindAdmit, e.id, e.key, pinOf('e'), reasonPaired)
	if preferredRecord(remove, admit).Kind != kindRemove || preferredRecord(admit, remove).Kind != kindRemove {
		t.Fatal("a removal must beat a record that is not one")
	}
	other := a.record(s, kindAdmit, "f", newTDev(t, "f").key, pinOf('f'), reasonPaired)
	lower := admit
	if other.hash() < admit.hash() {
		lower = other
	}
	if preferredRecord(admit, other).hash() != lower.hash() || preferredRecord(other, admit).hash() != lower.hash() {
		t.Fatal("otherwise the lower hash wins")
	}
}

func TestTheLogBuildsOneChainAndKeepsOnlyWhatIsAccepted(t *testing.T) {
	a, b, c := newTDev(t, "a"), newTDev(t, "b"), newTDev(t, "c")
	l := newChainLog(func() string { return "a" })
	if _, err := l.append(a.priv, a.id, kindGenesis, a.id, a.key, pinOf('a'), reasonPaired); err != nil {
		t.Fatal(err)
	}
	if _, err := l.append(a.priv, a.id, kindAdmit, b.id, b.key, pinOf('b'), reasonPaired); err != nil {
		t.Fatal(err)
	}
	if _, err := l.append(b.priv, b.id, kindAdmit, c.id, c.key, pinOf('c'), reasonPaired); err != nil {
		t.Fatal(err)
	}
	if got := l.view().State.activeIDs(); len(got) != 3 {
		t.Fatalf("expected three active members, got %v", got)
	}
	if _, err := l.append(c.priv, "x", kindAdmit, "d", "k", pinOf('d'), reasonPaired); err == nil {
		t.Fatal("a rejected record was appended")
	}
	if n := len(l.recordsAfter(0)); n != 3 {
		t.Fatalf("a rejected record must not be stored, have %d", n)
	}
}

func TestRecordsArriveInAnyOrderAndABadSignatureIsIgnored(t *testing.T) {
	a, b := newTDev(t, "a"), newTDev(t, "b")
	src := newChainLog(func() string { return "a" })
	src.append(a.priv, a.id, kindGenesis, a.id, a.key, pinOf('a'), reasonPaired)
	src.append(a.priv, a.id, kindAdmit, b.id, b.key, pinOf('b'), reasonPaired)
	records := src.recordsAfter(0)
	dst := newChainLog(func() string { return "z" })
	reversed := []chainRecord{records[1], records[0]}
	if n := dst.ingest(reversed); n != 2 {
		t.Fatalf("both records should be new, got %d", n)
	}
	if dst.ingest(reversed) != 0 {
		t.Fatal("a repeat was counted")
	}
	if got := dst.view().State.activeIDs(); len(got) != 2 {
		t.Fatalf("out of order records gave %v", got)
	}
	bad := records[1]
	bad.Reason = "tampered"
	if newChainLog(func() string { return "z" }).ingest([]chainRecord{bad}) != 0 {
		t.Fatal("a record with a bad signature was stored")
	}
}

func TestASecondGenesisIsAnotherClusterAndIsNeverStoredOrServedOn(t *testing.T) {
	a, b := newTDev(t, "a"), newTDev(t, "b")
	la := newChainLog(func() string { return "a" })
	la.append(a.priv, a.id, kindGenesis, a.id, a.key, pinOf('a'), reasonPaired)
	la.append(a.priv, a.id, kindAdmit, b.id, b.key, pinOf('b'), reasonPaired)
	lb := newChainLog(func() string { return "b" })
	lb.append(b.priv, b.id, kindGenesis, b.id, b.key, pinOf('b'), reasonPaired)
	lb.append(b.priv, b.id, kindAdmit, a.id, a.key, pinOf('a'), reasonPaired)
	la.ingest(lb.recordsAfter(0))
	lb.ingest(la.recordsAfter(0))
	if len(la.recordsAfter(0)) != 2 || la.recordsAfter(0)[0].Actor != "a" {
		t.Fatal("device a took in another cluster")
	}
	if len(lb.recordsAfter(0)) != 2 || lb.recordsAfter(0)[0].Actor != "b" {
		t.Fatal("device b took in another cluster")
	}
}

func TestADatabaseHoldingTwoGenesesFollowsTheOneThisDeviceWroteElseTheLowerHash(t *testing.T) {
	a, b := newTDev(t, "a"), newTDev(t, "b")
	ga := a.record(chainState{}, kindGenesis, a.id, a.key, pinOf('a'), reasonPaired)
	gb := b.record(chainState{}, kindGenesis, b.id, b.key, pinOf('b'), reasonPaired)
	for _, tc := range []struct{ self, want string }{{"a", "a"}, {"b", "b"}} {
		l := newChainLog(func() string { return tc.self })
		l.rawPut(ga)
		l.rawPut(gb)
		if got := l.view().Canonical[0].Actor; got != tc.want {
			t.Fatalf("self %s followed %s", tc.self, got)
		}
	}
	lower := ga
	if gb.hash() < ga.hash() {
		lower = gb
	}
	l := newChainLog(func() string { return "x" })
	l.rawPut(ga)
	l.rawPut(gb)
	if l.rootHash() != lower.hash() {
		t.Fatal("a device that wrote neither follows the lower hash")
	}
}

func TestClearingTheLogForgetsEverything(t *testing.T) {
	a := newTDev(t, "a")
	l := newChainLog(func() string { return "a" })
	l.append(a.priv, a.id, kindGenesis, a.id, a.key, pinOf('a'), reasonPaired)
	l.clear()
	if l.rootHash() != "" || len(l.recordsAfter(0)) != 0 {
		t.Fatal("a cleared log still holds records")
	}
	if _, err := l.append(a.priv, a.id, kindGenesis, a.id, a.key, pinOf('a'), reasonPaired); err != nil {
		t.Fatal("a cleared log must take a new genesis")
	}
}

type vectorMember struct {
	Key    string `json:"key"`
	TLSPin string `json:"tlsPin"`
	Active bool   `json:"active"`
}

type vectorFile struct {
	Records []struct {
		Record chainRecord `json:"record"`
		Hash   string      `json:"hash"`
	} `json:"records"`
	Views []struct {
		Self      string                  `json:"self"`
		Canonical []string                `json:"canonical"`
		Served    []string                `json:"served"`
		Members   map[string]vectorMember `json:"members"`
	} `json:"views"`
}

func TestTheChainTheKotlinCodeWroteHasTheSameHashesSignaturesAndViews(t *testing.T) {
	body, err := os.ReadFile("testdata/chain_vectors.json")
	if err != nil {
		t.Fatalf("the Kotlin vectors are missing: %v", err)
	}
	var v vectorFile
	if err := json.Unmarshal(body, &v); err != nil {
		t.Fatal(err)
	}
	if len(v.Records) == 0 || len(v.Views) == 0 {
		t.Fatal("empty vectors")
	}
	var records []chainRecord
	for _, e := range v.Records {
		if got := e.Record.hash(); got != e.Hash {
			t.Fatalf("hash of %s %d: Go %s, Kotlin %s", e.Record.Kind, e.Record.Seq, got, e.Hash)
		}
		if !verifyRecord(e.Record) {
			t.Fatalf("signature of %s %d does not verify in Go", e.Record.Kind, e.Record.Seq)
		}
		records = append(records, e.Record)
	}
	for _, want := range v.Views {
		l := newChainLog(func() string { return want.Self })
		for _, r := range records {
			l.rawPut(r)
		}
		view := l.view()
		var canonical []string
		for _, r := range view.Canonical {
			canonical = append(canonical, r.hash())
		}
		if strings.Join(canonical, ",") != strings.Join(want.Canonical, ",") {
			t.Fatalf("canonical chain for %s differs", want.Self)
		}
		var served []string
		for _, r := range l.recordsAfter(0) {
			served = append(served, r.hash())
		}
		sort.Strings(served)
		wantServed := append([]string(nil), want.Served...)
		sort.Strings(wantServed)
		if strings.Join(served, ",") != strings.Join(wantServed, ",") {
			t.Fatalf("records served for %s differ", want.Self)
		}
		if len(view.State.Members) != len(want.Members) {
			t.Fatalf("members for %s: Go %d, Kotlin %d", want.Self, len(view.State.Members), len(want.Members))
		}
		for id, m := range want.Members {
			got := view.State.Members[id]
			if got.Key != m.Key || got.TLSPin != m.TLSPin || got.Active != m.Active {
				t.Fatalf("member %s for %s: Go %+v, Kotlin %+v", id, want.Self, got, m)
			}
		}
	}
}
