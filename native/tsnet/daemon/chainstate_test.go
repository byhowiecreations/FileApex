package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func stateOf(seq int64, head, profile string) chainStateField {
	return chainStateField{Seq: seq, Head: head, Profile: profile}
}

func TestTheFieldRoundTripsAndKeepsTheFirst128BitsOfTheHead(t *testing.T) {
	f := newChainStateField(12, strings.Repeat("a", 64), "0a1b2c3d")
	if got := f.encode(); got != "v1.12."+strings.Repeat("a", 32)+".0a1b2c3d" {
		t.Fatalf("encoded %q", got)
	}
	back, ok := parseChainState(f.encode())
	if !ok || back != f {
		t.Fatalf("round trip gave %+v ok=%v", back, ok)
	}
	if none, ok := parseChainState("v1.0..00000000"); !ok || none.Seq != 0 || none.Head != "" {
		t.Fatalf("a device with no chain: %+v ok=%v", none, ok)
	}
}

// The same literal is parsed by the Kotlin ChainStateFieldTest, so the two sides cannot drift apart.
func TestTheSharedVectorParses(t *testing.T) {
	f, ok := parseChainState("v1.12." + strings.Repeat("a", 32) + ".0a1b2c3d")
	if !ok || f.Seq != 12 || f.Profile != "0a1b2c3d" {
		t.Fatalf("shared vector: %+v ok=%v", f, ok)
	}
}

func TestAnythingMalformedIsIgnored(t *testing.T) {
	good := "v1.5." + strings.Repeat("b", 32) + ".deadbeef"
	if _, ok := parseChainState(good); !ok {
		t.Fatal("the good one was refused")
	}
	for _, bad := range []string{
		"", "garbage", "v2.5." + strings.Repeat("b", 32) + ".deadbeef", "v1.-5." + strings.Repeat("b", 32) + ".deadbeef",
		"v1.5." + strings.Repeat("b", 31) + ".deadbeef", "v1.5." + strings.Repeat("B", 32) + ".deadbeef",
		"v1.5." + strings.Repeat("b", 32) + ".deadbee", "v1.99999999999." + strings.Repeat("b", 32) + ".deadbeef",
		"v1.0." + strings.Repeat("b", 32) + ".deadbeef", "v1.5..deadbeef", good + ".extra", good + strings.Repeat("x", 200), " " + good,
	} {
		if _, ok := parseChainState(bad); ok {
			t.Fatalf("accepted %q", bad)
		}
	}
}

func TestTheProfileDigestIgnoresAddressesAndFollowsEveryStableField(t *testing.T) {
	base := nodeState{DeviceID: "d", DeviceName: "Unraid", IPAddress: "10.0.0.13", LastKnownIP: "10.0.0.13", Port: 8080, ClientVersion: "docker", AppVersion: "0.20.5", Platform: "linux", OS: "linux", PublicKey: "pk", TLSPin: strings.Repeat("1", 64), TLSPort: 8081}
	d := profileDigest(base)
	if len(d) != 8 {
		t.Fatalf("digest %q", d)
	}
	moved := base
	moved.IPAddress, moved.LastKnownIP = "10.0.0.9", "10.0.0.9"
	if profileDigest(moved) != d {
		t.Fatal("an address changed the digest")
	}
	for name, change := range map[string]func(*nodeState){
		"name": func(s *nodeState) { s.DeviceName = "Renamed" }, "port": func(s *nodeState) { s.Port = 9090 },
		"version": func(s *nodeState) { s.AppVersion = "0.20.6" }, "key": func(s *nodeState) { s.PublicKey = "other" },
		"pin": func(s *nodeState) { s.TLSPin = strings.Repeat("2", 64) }, "tls port": func(s *nodeState) { s.TLSPort = 9443 },
	} {
		next := base
		change(&next)
		if profileDigest(next) == d {
			t.Fatalf("%s did not change the digest", name)
		}
	}
}

type watchRig struct {
	mine   chainStateField
	clock  time.Time
	pulled []string
	reply  bool
	adopt  *chainStateField
	watch  *chainWatch
}

func newWatchRig(seq int64, head string) *watchRig {
	r := &watchRig{mine: newChainStateField(seq, head, "00000001"), clock: time.Unix(1_000_000, 0), reply: true}
	r.watch = newChainWatch(func() chainStateField { return r.mine }, func(id string) bool {
		r.pulled = append(r.pulled, id)
		if r.reply && r.adopt != nil {
			r.mine = *r.adopt
		}
		return r.reply
	}, func() time.Time { return r.clock })
	return r
}

func hx(c byte) string { return strings.Repeat(string(c), 64) }

func TestAnEqualStateCostsNothing(t *testing.T) {
	r := newWatchRig(5, hx('a'))
	r.watch.note("p", newChainStateField(5, hx('a'), "00000001"))
	if len(r.pulled) != 0 {
		t.Fatal("pulled on an equal state")
	}
}

func TestAPeerAheadIsPulledOnceAndABehindOrEmptyPeerNever(t *testing.T) {
	r := newWatchRig(5, hx('a'))
	r.adopt = &chainStateField{Seq: 6, Head: hx('b')[:32], Profile: "00000001"}
	r.watch.note("p", newChainStateField(6, hx('b'), "00000001"))
	r.watch.note("p", newChainStateField(6, hx('b'), "00000001"))
	if len(r.pulled) != 1 {
		t.Fatalf("pulled %v, want once", r.pulled)
	}
	r2 := newWatchRig(5, hx('a'))
	r2.watch.note("q", newChainStateField(4, hx('c'), "00000001"))
	r2.watch.note("q", newChainStateField(0, "", "00000000"))
	if len(r2.pulled) != 0 {
		t.Fatalf("a peer behind or with no chain was pulled: %v", r2.pulled)
	}
}

func TestAForkIsPulledOncePerHeadThenOnTheBackOffTable(t *testing.T) {
	r := newWatchRig(5, hx('a'))
	fork := newChainStateField(5, hx('b'), "00000001")
	step := func(d time.Duration) { r.clock = r.clock.Add(d); r.watch.note("p", fork) }
	r.watch.note("p", fork)
	want := []struct {
		after time.Duration
		pulls int
	}{{11 * time.Second, 1}, {61 * time.Second, 2}, {4 * time.Minute, 2}, {61 * time.Second, 3}, {29 * time.Minute, 3}, {2 * time.Minute, 4}, {61 * time.Minute, 5}}
	for _, w := range want {
		step(w.after)
		if len(r.pulled) != w.pulls {
			t.Fatalf("after %v: %d pulls, want %d", w.after, len(r.pulled), w.pulls)
		}
	}
}

func TestAProvenPeerGetsTheStateOnHealthAndShowsItsOwnAStrangerNeither(t *testing.T) {
	host := startedNode(t)
	writeGenesis(t, host)
	caller := pinnedPeer(t, host, "other")
	host.mu.Lock()
	host.peers["other"] = deviceRecord{DeviceID: "other", DeviceName: "other", LastKnownIP: "192.168.1.9", Port: 8080}
	host.mu.Unlock()

	rec := httptest.NewRecorder()
	req := caller.request(http.MethodGet, "/api/v1/health?cs=v1.9."+strings.Repeat("e", 32)+".01020304", nil)
	host.handleHealth(rec, req)
	got, ok := parseChainState(rec.Header().Get(chainStateHeader))
	if !ok || got.Seq != host.chain.view().State.Seq {
		t.Fatalf("a proven peer got %q", rec.Header().Get(chainStateHeader))
	}
	if shown, ok := host.watch.fieldOf("other"); !ok || shown.Seq != 9 {
		t.Fatalf("the state the peer showed was not kept: %+v ok=%v", shown, ok)
	}

	stranger := httptest.NewRecorder()
	host.handleHealth(stranger, httptest.NewRequest(http.MethodGet, "/api/v1/health?cs=v1.9."+strings.Repeat("e", 32)+".01020304", nil))
	if stranger.Header().Get(chainStateHeader) != "" {
		t.Fatal("a stranger got the chain state")
	}
	if _, ok := host.watch.fieldOf("nobody"); ok {
		t.Fatal("a stranger's claim was kept")
	}
}
