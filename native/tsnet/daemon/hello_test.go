package main

import (
	"bytes"
	"crypto/ecdh"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"testing"
	"time"
)

// The Kotlin side (HelloAuthenticatorTest) computes the same value with JDK primitives.
const helloVector = "3df65d5bb2f21f906eada75d86c28bd4b9412a559d01ff911da9504842eb16e5"

func TestHelloKeyMatchesKotlin(t *testing.T) {
	a, _ := ecdh.X25519().NewPrivateKey(bytes.Repeat([]byte{7}, 32))
	b, _ := ecdh.X25519().NewPrivateKey(bytes.Repeat([]byte{9}, 32))
	pubB := base64.StdEncoding.EncodeToString(b.PublicKey().Bytes())
	key, err := pairKeyFor(a, pubB, "x|y", helloInfo)
	if err != nil {
		t.Fatal(err)
	}
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte("hello header"))
	if got := hex.EncodeToString(mac.Sum(nil)); got != helloVector {
		t.Fatalf("proof %s, want %s", got, helloVector)
	}
}

func TestHelloKeyIsNotTheClipboardKey(t *testing.T) {
	a, _ := ecdh.X25519().NewPrivateKey(bytes.Repeat([]byte{7}, 32))
	b, _ := ecdh.X25519().NewPrivateKey(bytes.Repeat([]byte{9}, 32))
	pubB := base64.StdEncoding.EncodeToString(b.PublicKey().Bytes())
	hello, _ := pairKeyFor(a, pubB, "x|y", helloInfo)
	clip, _ := pairKey(a, pubB, "x|y")
	if bytes.Equal(hello, clip) {
		t.Fatal("hello and clipboard keys must differ")
	}
}

func TestParseHelloRejectsOtherPayloads(t *testing.T) {
	for _, raw := range [][]byte{nil, []byte("WAKE_FILEAPEX"), []byte("FAH1"), bytes.Repeat([]byte{'x'}, 600)} {
		if _, ok := parseHello(raw); ok {
			t.Fatalf("parsed %q", raw)
		}
	}
}

func TestParseHelloReadsAKotlinLayout(t *testing.T) {
	id := "A"
	header := []byte("FAH1")
	header = append(header, byte(len(id)))
	header = append(header, id...)
	header = append(header, 0x1f, 0x90) // port 8080
	header = append(header, 1, 2, 3, 4, 5, 6, 7, 8)
	header = append(header, 0, 0, 0, 0, 0, 0, 0x27, 0x10) // 10000 ms
	tag := helloTag("B")
	packet := append(append([]byte(nil), header...), 1)
	packet = append(packet, tag[:]...)
	packet = append(packet, bytes.Repeat([]byte{9}, helloMacBytes)...)

	p, ok := parseHello(packet)
	if !ok || p.senderID != "A" || p.port != 8080 || p.sentAt != 10000 || p.ack || len(p.entries) != 1 {
		t.Fatalf("parsed %+v ok=%v", p, ok)
	}
	if !bytes.Equal(p.signed, header) {
		t.Fatal("signed bytes must be the header up to and including the time")
	}
	if p.entries[0].tag != tag {
		t.Fatal("tag mismatch")
	}
}

func TestHelloGateDropsReplayAndRapidRepeat(t *testing.T) {
	var g helloGate
	now := time.Now()
	n1 := [helloNonceLen]byte{1}
	n2 := [helloNonceLen]byte{2}
	if !g.accept("hello", "A", n1, now) {
		t.Fatal("first must pass")
	}
	if g.accept("hello", "A", n1, now.Add(time.Minute)) {
		t.Fatal("replayed nonce must fail")
	}
	if g.accept("hello", "A", n2, now.Add(time.Second)) {
		t.Fatal("rapid repeat must fail")
	}
	if !g.accept("hello", "A", n2, now.Add(helloMinGap)) {
		t.Fatal("repeat after the gap must pass")
	}
}
