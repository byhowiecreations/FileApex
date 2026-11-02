package main

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// ---- the applier, against recorded effects ----

type fakeEffects struct {
	pins       map[string]string
	profiles   []string
	removedIDs map[string]string
	selfBy     []string
	handled    string
	marked     []string
}

func newFakeEffects() *fakeEffects {
	return &fakeEffects{pins: map[string]string{}, removedIDs: map[string]string{}}
}
func (f *fakeEffects) knownPin(id string) (string, bool) { p, ok := f.pins[id]; return p, ok }
func (f *fakeEffects) setPin(id, pin string)             { f.pins[id] = pin }
func (f *fakeEffects) ensureProfile(id string)           { f.profiles = append(f.profiles, id) }
func (f *fakeEffects) selfRemoved(by, hash string)       { f.selfBy = append(f.selfBy, by) }
func (f *fakeEffects) handledRemoval() string            { return f.handled }
func (f *fakeEffects) markHandled(hash string)           { f.handled = hash; f.marked = append(f.marked, hash) }
func (f *fakeEffects) knownRemoved(id string) bool       { _, ok := f.removedIDs[id]; return ok }
func (f *fakeEffects) markRemoved(id, by, reason, pin string) {
	f.removedIDs[id] = reason
}

type clusterOfThree struct {
	a, b, c tdev
	state   chainState
	records []chainRecord
}

func threeMembers(t *testing.T) clusterOfThree {
	t.Helper()
	a, b, c := newTDev(t, "a"), newTDev(t, "b"), newTDev(t, "c")
	g := a.record(chainState{}, kindGenesis, "a", a.key, pinOf('a'), reasonPaired)
	state := mustApply(t, chainState{}, g)
	records := []chainRecord{g}
	for _, d := range []tdev{b, c} {
		r := a.record(state, kindAdmit, d.id, d.key, pinOf(d.id[0]), reasonPaired)
		state = mustApply(t, state, r)
		records = append(records, r)
	}
	return clusterOfThree{a: a, b: b, c: c, state: state, records: records}
}

func viewOf(t *testing.T, records []chainRecord) chainView {
	t.Helper()
	log := newChainLog(func() string { return "" })
	log.ingest(records)
	return log.view()
}

func TestTheApplierFollowsActiveMembersPinsAndNeverAKeylessOnesOverAHeldPin(t *testing.T) {
	c := threeMembers(t)
	fx := newFakeEffects()
	fx.pins["b"] = "held-pin-of-b"
	applier := newChainApplier("a", fx, time.Now)
	applier.apply(viewOf(t, c.records))
	if fx.pins["c"] != pinOf('c') {
		t.Fatalf("c's pin: %q", fx.pins["c"])
	}
	if fx.pins["b"] != pinOf('b') {
		t.Fatalf("a member with a key of its own is followed by the chain: %q", fx.pins["b"])
	}
	if len(fx.profiles) != 2 {
		t.Fatalf("profiles ensured for %v", fx.profiles)
	}

	keyless := newChainLog(func() string { return "" })
	keyless.ingest(c.records[:2])
	state := keyless.view().State
	record := c.a.record(state, kindAdmit, "legacy-peer", "legacy:legacy-peer", pinOf('d'), reasonPaired)
	keyless.ingest([]chainRecord{record})
	fx2 := newFakeEffects()
	fx2.pins["legacy-peer"] = "confirmed-by-the-user"
	newChainApplier("a", fx2, time.Now).apply(keyless.view())
	if fx2.pins["legacy-peer"] != "confirmed-by-the-user" {
		t.Fatal("a key-less admit overrode a pin that pairing confirmed")
	}
}

func TestTheApplierDropsWhatTheChainRemovedOrReleasedAndReportsOurOwnRemovalOnce(t *testing.T) {
	c := threeMembers(t)
	remove := c.a.record(c.state, kindRemove, "c", c.c.key, pinOf('c'), reasonUserRemoved)
	afterRemove := mustApply(t, c.state, remove)
	leave := c.b.record(afterRemove, kindLeave, "b", c.b.key, pinOf('b'), reasonLeft)
	records := append(append([]chainRecord{}, c.records...), remove, leave)

	fx := newFakeEffects()
	applier := newChainApplier("a", fx, time.Now)
	applier.apply(viewOf(t, records))
	if fx.removedIDs["c"] != reasonUserRemoved || fx.removedIDs["b"] != reasonLeft {
		t.Fatalf("removed: %v", fx.removedIDs)
	}

	asC := newFakeEffects()
	asCApplier := newChainApplier("c", asC, time.Now)
	asCApplier.apply(viewOf(t, records[:4]))
	asCApplier.apply(viewOf(t, records[:4]))
	if len(asC.selfBy) != 1 || asC.selfBy[0] != "a" {
		t.Fatalf("self removal reported %v", asC.selfBy)
	}
}

func TestAnAlreadyPresentRemovalIsHistoryAndALaterLeaveIsAppliedAtOnce(t *testing.T) {
	c := threeMembers(t)
	remove := c.a.record(c.state, kindRemove, "c", c.c.key, pinOf('c'), reasonUserRemoved)
	withRemoval := append(append([]chainRecord{}, c.records...), remove)

	fx := newFakeEffects()
	applier := newChainApplier("c", fx, time.Now)
	view := viewOf(t, withRemoval)
	applier.acknowledgeHistory(view)
	applier.apply(view)
	if len(fx.selfBy) != 0 {
		t.Fatalf("a removal that was already in the chain taken in person wiped the node: %v", fx.selfBy)
	}

	// b, paired in person at chain seq 4, then leaves: that is news, not history.
	applier.pairedInPerson("b", 4)
	leave := c.b.record(mustApply(t, c.state, remove), kindLeave, "b", c.b.key, pinOf('b'), reasonLeft)
	applier.apply(viewOf(t, append(withRemoval, leave)))
	if fx.removedIDs["b"] != reasonLeft {
		t.Fatalf("a leave after the pairing was held back: %v", fx.removedIDs)
	}
}

func TestARemovalAlreadyInTheChainAtThePairingIsHeldBackForTheGraceOnly(t *testing.T) {
	c := threeMembers(t)
	remove := c.a.record(c.state, kindRemove, "b", c.b.key, pinOf('b'), reasonUserRemoved)
	records := append(append([]chainRecord{}, c.records...), remove)
	fx := newFakeEffects()
	now := time.Now()
	applier := newChainApplier("a", fx, func() time.Time { return now })
	applier.pairedInPerson("b", 4)
	applier.apply(viewOf(t, records))
	if _, held := fx.removedIDs["b"]; held {
		t.Fatal("the removal was applied during the grace")
	}
	now = now.Add(11 * time.Minute)
	applier.apply(viewOf(t, records))
	if fx.removedIDs["b"] != reasonUserRemoved {
		t.Fatal("the removal stayed held back after the grace")
	}
}

// ---- two real daemons ticking against each other through the real routes ----

func startedNode(t *testing.T) *Node {
	t.Helper()
	node, err := newNode(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	node.port = 0
	node.allowLoopback = true
	node.dialTimeout = 500 * time.Millisecond
	node.announceTimeout = 500 * time.Millisecond
	if err := node.serve(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { node.shutdown(time.Second) })
	waitForHealth(t, node.listenPort())
	return node
}

// link makes two nodes pinned peers of each other on loopback, as pairing leaves them.
func link(a, b *Node) {
	for _, pair := range [][2]*Node{{a, b}, {b, a}} {
		from, to := pair[0], pair[1]
		from.tls.record(to.deviceID(), to.tlsPinValue(), to.tlsPort())
		from.mu.Lock()
		from.joined = true
		from.peers[to.deviceID()] = deviceRecord{DeviceID: to.deviceID(), DeviceName: to.deviceName(), LastKnownIP: "127.0.0.1", Port: to.listenPort()}
		from.mu.Unlock()
	}
}

func hostAdmits(t *testing.T, host, joiner *Node) {
	t.Helper()
	if host.chain.view().State.Seq == 0 {
		writeGenesis(t, host)
	}
	_, key := joiner.signer()
	if !host.admit(joiner.deviceID(), key, joiner.tlsPinValue()) {
		t.Fatal("the host did not admit the joiner")
	}
}

func TestAJoinerThatIsOnTheRosterButNotInTheChainAsksAnActiveMemberAndIsAdmitted(t *testing.T) {
	host, joiner := startedNode(t), startedNode(t)
	link(host, joiner)
	writeGenesis(t, host)

	joiner.tickChain(context.Background())

	member, ok := joiner.chain.view().State.Members[joiner.deviceID()]
	_, key := joiner.signer()
	if !ok || !member.Active || member.Key != key {
		t.Fatalf("joiner in its own chain: %+v present=%v", member, ok)
	}
	if hostView := host.chain.view().State.Members[joiner.deviceID()]; !hostView.Active {
		t.Fatalf("host does not hold the joiner as active: %+v", hostView)
	}
}

func TestAKeylessMemberBindsItsRealKeyInPlace(t *testing.T) {
	host, joiner := startedNode(t), startedNode(t)
	link(host, joiner)
	writeGenesis(t, host)
	signing, _ := host.signer()
	if _, err := host.chain.append(signing, host.deviceID(), kindAdmit, joiner.deviceID(), legacyKeyPrefix+joiner.deviceID(), joiner.tlsPinValue(), reasonPaired); err != nil {
		t.Fatal(err)
	}

	joiner.tickChain(context.Background())

	member := joiner.chain.view().State.Members[joiner.deviceID()]
	_, key := joiner.signer()
	if !member.Active || member.Key != key {
		t.Fatalf("a key-less member did not take its real key: %+v", member)
	}
}

func TestBeingRemovedByTheHostWipesTheClusterAndRenewsBothKeys(t *testing.T) {
	host, joiner := startedNode(t), startedNode(t)
	link(host, joiner)
	hostAdmits(t, host, joiner)
	joiner.tickChain(context.Background())
	oldKey, oldPin := keyOf(joiner), joiner.tlsPinValue()

	hostKey, _ := host.signer()
	member := host.chain.view().State.Members[joiner.deviceID()]
	if _, err := host.chain.append(hostKey, host.deviceID(), kindRemove, joiner.deviceID(), member.Key, member.TLSPin, reasonUserRemoved); err != nil {
		t.Fatal(err)
	}

	joiner.tickChain(context.Background())

	if joiner.isJoined() {
		t.Fatal("the removed node still counts itself joined")
	}
	if len(joiner.roster()) != 0 || joiner.chain.view().State.Seq != 0 {
		t.Fatalf("the cluster was not let go: roster=%d chainSeq=%d", len(joiner.roster()), joiner.chain.view().State.Seq)
	}
	if keyOf(joiner) == oldKey || joiner.tlsPinValue() == oldPin {
		t.Fatal("a key survived the removal")
	}
	if _, err := os.Stat(filepath.Join(joiner.dataDir, "chain_signing.key")); err != nil {
		t.Fatal("the renewed signing key was not saved")
	}
}

func TestLeavingWritesALeaveThePeerAppliesAtOnce(t *testing.T) {
	host, joiner := startedNode(t), startedNode(t)
	link(host, joiner)
	hostAdmits(t, host, joiner)
	joiner.tickChain(context.Background())

	joiner.leaveCluster(context.Background())

	if joiner.isJoined() || len(joiner.roster()) != 0 {
		t.Fatal("the node kept its cluster after leaving")
	}
	hostView := host.chain.view().State
	if hostView.Members[joiner.deviceID()].Active {
		t.Fatal("the host still holds the leaver as active")
	}
	if len(host.roster()) != 0 {
		t.Fatalf("the host kept the leaver on its roster: %v", host.roster())
	}
	if !host.tls.isRevoked(joiner.deviceID()) {
		t.Fatal("the leaver's key was not kept as revoked")
	}
}

func TestAPeerReadsItsRemovalOverItsRevokedKeyAndNothingElse(t *testing.T) {
	host, joiner := startedNode(t), startedNode(t)
	link(host, joiner)
	hostAdmits(t, host, joiner)
	joiner.tickChain(context.Background())
	hostKey, _ := host.signer()
	member := host.chain.view().State.Members[joiner.deviceID()]
	if _, err := host.chain.append(hostKey, host.deviceID(), kindRemove, joiner.deviceID(), member.Key, member.TLSPin, reasonUserRemoved); err != nil {
		t.Fatal(err)
	}
	host.applyChainNow()
	if !host.tls.isRevoked(joiner.deviceID()) || len(host.roster()) != 0 {
		t.Fatal("the host did not let the removed device go")
	}
	// The removed joiner still has the host on its roster and pinned: it reads the chain and learns it was removed.
	joiner.tickChain(context.Background())
	if joiner.isJoined() {
		t.Fatal("the removed node never learned of its removal from a host that revoked it")
	}
}

func TestTakingTheChainInPersonMakesAnOldRemovalHistoryAndTheNodeJoinsWithANewKey(t *testing.T) {
	host, joiner := startedNode(t), startedNode(t)
	link(host, joiner)
	hostAdmits(t, host, joiner)
	hostKey, _ := host.signer()
	member := host.chain.view().State.Members[joiner.deviceID()]
	if _, err := host.chain.append(hostKey, host.deviceID(), kindRemove, joiner.deviceID(), member.Key, member.TLSPin, reasonUserRemoved); err != nil {
		t.Fatal(err)
	}
	host.applyChainNow()
	// The host forgot the revoked pin on purpose of this test only: pairing in person pins the joiner again.
	host.tls.record(joiner.deviceID(), joiner.tlsPinValue(), joiner.tlsPort())
	host.mu.Lock()
	host.peers[joiner.deviceID()] = deviceRecord{DeviceID: joiner.deviceID(), DeviceName: joiner.deviceName(), LastKnownIP: "127.0.0.1", Port: joiner.listenPort()}
	host.mu.Unlock()
	old := keyOf(joiner)

	if !joiner.joinClusterOf(context.Background(), host.deviceID()) {
		t.Fatal("could not take the host's chain")
	}
	if !joiner.isJoined() {
		t.Fatal("an old removal in the chain it just took wiped the node")
	}
	joiner.tickChain(context.Background())

	state := joiner.chain.view().State.Members[joiner.deviceID()]
	if !state.Active || state.Key == old || state.Key != keyOf(joiner) {
		t.Fatalf("the node did not rejoin under a new key: %+v (old %s)", state, old[:8])
	}
}

func TestTheHostsClusterReplacesTheNodesOwnWhenTheRootsDiffer(t *testing.T) {
	host, joiner := startedNode(t), startedNode(t)
	link(host, joiner)
	writeGenesis(t, host)
	writeGenesis(t, joiner)
	other := joiner.chain.view().State.Head

	if !joiner.joinClusterOf(context.Background(), host.deviceID()) {
		t.Fatal("could not take the host's chain")
	}
	if joiner.chain.view().State.Head == other {
		t.Fatal("the node kept its own cluster")
	}
	if joiner.chain.rootHash() != host.chain.rootHash() {
		t.Fatal("the node did not follow the host's root")
	}
}

func TestAChangedTLSKeyIsAnnouncedAsASignedPinRecord(t *testing.T) {
	host, joiner := startedNode(t), startedNode(t)
	link(host, joiner)
	hostAdmits(t, host, joiner)
	joiner.tickChain(context.Background())
	if _, err := joiner.tls.renewIdentity(time.Now()); err != nil {
		t.Fatal(err)
	}

	joiner.tickChain(context.Background())

	if got := joiner.chain.view().State.Members[joiner.deviceID()].TLSPin; got != joiner.tlsPinValue() {
		t.Fatalf("the chain holds pin %s, the node's key is %s", got, joiner.tlsPinValue())
	}
}

func TestProfilesFillInNamesAndTheTLSPortOfActiveMembersOnly(t *testing.T) {
	host, joiner := startedNode(t), startedNode(t)
	link(host, joiner)
	hostAdmits(t, host, joiner)
	host.mu.Lock()
	host.peers[joiner.deviceID()] = deviceRecord{DeviceID: joiner.deviceID(), DeviceName: "stub", LastKnownIP: "127.0.0.1", Port: joiner.listenPort()}
	host.mu.Unlock()
	host.tls.record(joiner.deviceID(), joiner.tlsPinValue(), 0)

	state := joiner.selfState()
	state.DeviceName = "Renamed"
	host.takeProfiles([]nodeState{state, {DeviceID: "stranger", DeviceName: "Nope", Port: 1}})

	if got := host.peers[joiner.deviceID()].DeviceName; got != "Renamed" {
		t.Fatalf("name %q", got)
	}
	if p, _ := host.tls.peer(joiner.deviceID()); p.Port != joiner.tlsPort() {
		t.Fatalf("tls port %d, want %d", p.Port, joiner.tlsPort())
	}
	if _, ok := host.peers["stranger"]; ok {
		t.Fatal("a profile for a device the chain does not hold became a peer")
	}
}

func keyOf(n *Node) string {
	_, key := n.signer()
	return key
}
