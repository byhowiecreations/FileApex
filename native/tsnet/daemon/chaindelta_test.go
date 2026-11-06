package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// fourRecordLog is a chain of one genesis and three admits written by a.
func fourRecordLog(t *testing.T) (*chainLog, tdev, []chainRecord) {
	t.Helper()
	a := newTDev(t, "a")
	l := newChainLog(func() string { return "a" })
	if _, err := l.append(a.priv, a.id, kindGenesis, a.id, a.key, pinOf('a'), reasonPaired); err != nil {
		t.Fatal(err)
	}
	for _, id := range []string{"b", "c", "d"} {
		peer := newTDev(t, id)
		if _, err := l.append(a.priv, a.id, kindAdmit, peer.id, peer.key, pinOf(id[0]), reasonPaired); err != nil {
			t.Fatal(err)
		}
	}
	return l, a, l.recordsAfter(0)
}

func TestRecordsAfterAHeadGiveOnlyTheNewerOnes(t *testing.T) {
	l, _, all := fourRecordLog(t)
	got, ok := l.recordsAfterHead(all[1].hash())
	if !ok || len(got) != 2 || got[0].Seq != 3 || got[1].Seq != 4 {
		t.Fatalf("records after the second: %v ok=%v", got, ok)
	}
	own, ok := l.recordsAfterHead(l.view().State.Head)
	if !ok || len(own) != 0 {
		t.Fatalf("the own head must give an empty answer, got %v ok=%v", own, ok)
	}
}

func TestAnUnknownEmptyOrLosingHeadGivesNoDelta(t *testing.T) {
	l, a, all := fourRecordLog(t)
	if _, ok := l.recordsAfterHead(""); ok {
		t.Fatal("an empty head must not give a delta")
	}
	if _, ok := l.recordsAfterHead(strings.Repeat("f", 64)); ok {
		t.Fatal("an unknown head must not give a delta")
	}
	parent := chainState{Seq: all[1].Seq, Head: all[1].hash()}
	e, f := newTDev(t, "e"), newTDev(t, "f")
	x := a.record(parent, kindAdmit, e.id, e.key, pinOf('e'), reasonPaired)
	y := a.record(parent, kindAdmit, f.id, f.key, pinOf('f'), reasonPaired)
	l.ingest([]chainRecord{x, y})
	winner := preferredRecord(x, y)
	loser := x
	if winner.hash() == x.hash() {
		loser = y
	}
	if _, ok := l.recordsAfterHead(loser.hash()); ok {
		t.Fatal("a head on a losing branch must not give a delta")
	}
}

func TestAGoodSignatureIsCheckedOncePerRecord(t *testing.T) {
	l, _, all := fourRecordLog(t)
	resetVerifyCache()
	for i := 0; i < 10; i++ {
		l.view()
	}
	l.ingest(all)
	if got := signatureChecks(); got != int64(len(all)) {
		t.Fatalf("%d signature checks for %d records read ten times", got, len(all))
	}
}

func TestABadSignatureIsNeverRemembered(t *testing.T) {
	_, _, all := fourRecordLog(t)
	resetVerifyCache()
	forged := all[1]
	forged.Signature = all[0].Signature
	if verifyRecord(forged) || verifyRecord(forged) {
		t.Fatal("a forged record verified")
	}
	if !verifyRecord(all[1]) {
		t.Fatal("the real record stopped verifying")
	}
}

func TestTheVerifiedSetHasABound(t *testing.T) {
	a := newTDev(t, "a")
	resetVerifyCache()
	for i := 0; i < verifiedLimit+50; i++ {
		r := a.record(chainState{}, kindGenesis, a.id, a.key, pinOf('a'), "paired-"+strings.Repeat("x", i%7)+string(rune('a'+i%26))+strings.Repeat("y", i/26))
		if !verifyRecord(r) {
			t.Fatal("a signed record did not verify")
		}
	}
	if got := verifiedSize(); got > verifiedLimit {
		t.Fatalf("verified set holds %d, limit %d", got, verifiedLimit)
	}
}

func TestTheAnswerFollowsWhatTheAskerHolds(t *testing.T) {
	host := startedNode(t)
	writeGenesis(t, host)
	key, pub := host.signer()
	for _, id := range []string{"b", "c"} {
		peer := newTDev(t, id)
		if _, err := host.chain.append(key, host.deviceID(), kindAdmit, peer.id, peer.key, pinOf(id[0]), reasonPaired); err != nil {
			t.Fatal(err)
		}
	}
	_ = pub
	all := host.chain.recordsAfter(0)
	if got := host.answerChain(true, ""); len(got.Records) != 3 {
		t.Fatalf("no head: %d records, want all 3", len(got.Records))
	}
	if got := host.answerChain(true, all[0].hash()); len(got.Records) != 2 || got.Head != all[2].hash() {
		t.Fatalf("one behind: %d records head %s", len(got.Records), got.Head)
	}
	if got := host.answerChain(true, all[2].hash()); len(got.Records) != 0 || got.Head != all[2].hash() || len(got.Profiles) != 0 {
		t.Fatalf("equal: %d records, %d profiles, want head only", len(got.Records), len(got.Profiles))
	}
	if got := host.answerChain(true, all[0].hash()); len(got.Profiles) == 0 {
		t.Fatal("a change brings the profiles")
	}
	if got := host.answerChain(true, strings.Repeat("0", 64)); len(got.Records) != 3 {
		t.Fatalf("unknown head: %d records, want all", len(got.Records))
	}
	if got := host.answerChain(true, "not-a-hash"); len(got.Records) != 3 {
		t.Fatalf("a claim that is not a hash: %d records, want all", len(got.Records))
	}
	served := host.chainServed()
	if served.empty != 1 || served.delta != 2 || served.full != 3 {
		t.Fatalf("served counters %+v", served)
	}
}

func TestAPullOverTheWireCarriesTheHeadAndAPushIsAnsweredByIt(t *testing.T) {
	host, joiner := startedNode(t), startedNode(t)
	link(host, joiner)
	hostAdmits(t, host, joiner)
	joiner.tickChain(context.Background())
	all := host.chain.recordsAfter(0)
	head := host.chain.view().State.Head

	caller := pinnedPeer(t, host, "other")
	host.mu.Lock()
	host.peers["other"] = deviceRecord{DeviceID: "other", DeviceName: "other", LastKnownIP: "192.168.1.9", Port: 8080}
	host.mu.Unlock()
	rec := httptest.NewRecorder()
	host.handleChain(rec, caller.request(http.MethodGet, chainPath+"?since="+head, nil))
	var answer chainAnswer
	if err := json.Unmarshal(rec.Body.Bytes(), &answer); err != nil || rec.Code != http.StatusOK {
		t.Fatalf("pull: %d %v", rec.Code, err)
	}
	if len(answer.Records) != 0 || answer.Head != head {
		t.Fatalf("an equal head must give no records, got %d", len(answer.Records))
	}

	body, _ := json.Marshal(chainPush{Records: nil, Head: all[0].hash()})
	rec = httptest.NewRecorder()
	host.handleChain(rec, caller.request(http.MethodPost, chainPath, body))
	json.Unmarshal(rec.Body.Bytes(), &answer)
	if len(answer.Records) != len(all)-1 {
		t.Fatalf("a push from one behind: %d records, want %d", len(answer.Records), len(all)-1)
	}

	old, _ := json.Marshal(map[string]any{"records": []chainRecord{}, "profiles": []nodeState{}})
	rec = httptest.NewRecorder()
	host.handleChain(rec, caller.request(http.MethodPost, chainPath, old))
	json.Unmarshal(rec.Body.Bytes(), &answer)
	if len(answer.Records) != len(all) {
		t.Fatalf("an old push without a head: %d records, want all %d", len(answer.Records), len(all))
	}
}

func TestWhatWePushDependsOnTheHeadWeSawFromThePeer(t *testing.T) {
	l, _, all := fourRecordLog(t)
	n := &Node{chain: l}
	records, delta := n.recordsForPeer(all[1].hash())
	if !delta || len(records) != 2 {
		t.Fatalf("a known head: delta=%v %d records", delta, len(records))
	}
	records, delta = n.recordsForPeer("")
	if delta || len(records) != 4 {
		t.Fatalf("no head seen: delta=%v %d records", delta, len(records))
	}
	records, delta = n.recordsForPeer(strings.Repeat("e", 64))
	if delta || len(records) != 4 {
		t.Fatalf("an unknown head: delta=%v %d records", delta, len(records))
	}
}
