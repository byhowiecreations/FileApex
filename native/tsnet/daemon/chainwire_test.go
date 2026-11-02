package main

import (
	"encoding/json"
	"os"
	"testing"
)

// The chain routes' JSON is written by Kotlin and by Go and read by the other. Each side writes a file with what its
// own code produces; each side's test reads the other's file. A change to a field on either side breaks one of them.

type wireFile struct {
	Answer chainAnswer      `json:"answer"`
	Push   chainPush        `json:"push"`
	Join   chainJoinRequest `json:"join"`
}

// withDocumentationAddress keeps the address of the machine that ran the test out of a file that is committed.
func withDocumentationAddress(state nodeState) nodeState {
	state.IPAddress = "192.0.2.10"
	state.LastKnownIP = "192.0.2.10"
	return state
}

func TestWriteTheWireTheKotlinCodeIsCheckedAgainst(t *testing.T) {
	out := os.Getenv("GO_WIRE_OUT")
	if out == "" {
		t.Skip("set GO_WIRE_OUT to write the file")
	}
	host, joiner := startedNode(t), startedNode(t)
	link(host, joiner)
	hostAdmits(t, host, joiner)
	joiner.tickChain(t.Context())
	own := withDocumentationAddress(joiner.selfState())
	answer := host.answerChain(true)
	for i := range answer.Profiles {
		answer.Profiles[i] = withDocumentationAddress(answer.Profiles[i])
	}
	file := wireFile{
		Answer: answer,
		Push:   chainPush{Records: host.chain.recordsAfter(0), Profiles: []nodeState{own}},
		Join:   chainJoinRequest{Key: keyOf(joiner), TLSPin: joiner.tlsPinValue(), Profile: &own},
	}
	body, err := json.MarshalIndent(file, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(out, body, 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestTheKotlinWireIsReadByTheDaemon(t *testing.T) {
	body, err := os.ReadFile("testdata/kotlin_chain_wire.json")
	if err != nil {
		t.Fatalf("the Kotlin wire file is missing: %v", err)
	}
	var file wireFile
	if err := json.Unmarshal(body, &file); err != nil {
		t.Fatal(err)
	}
	if len(file.Answer.Records) < 2 || file.Answer.Head != file.Answer.Records[len(file.Answer.Records)-1].hash() {
		t.Fatalf("the answer's head %q is not its last record", file.Answer.Head)
	}
	for _, r := range append(file.Answer.Records, file.Push.Records...) {
		if !verifyRecord(r) {
			t.Fatalf("record %d of kind %s does not verify", r.Seq, r.Kind)
		}
	}
	if len(file.Answer.Profiles) == 0 || file.Answer.Profiles[0].DeviceID == "" || file.Answer.Profiles[0].Port == 0 || file.Answer.Profiles[0].TLSPort == 0 {
		t.Fatalf("answer profiles %+v", file.Answer.Profiles)
	}
	if file.Join.Key == "" || file.Join.Profile == nil || file.Join.Profile.DeviceName == "" {
		t.Fatalf("join request %+v", file.Join)
	}
	if len(file.Push.Profiles) == 0 {
		t.Fatal("push carried no profiles")
	}
}
