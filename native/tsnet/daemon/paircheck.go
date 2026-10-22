package main

import (
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"strings"
)

// The number both screens show while two devices pair, and the commitment that stops an attacker grinding keys until
// the two legs show the same digits. They must equal the app's PairingSas: the same vectors are asserted in the Kotlin
// tests (PairingSasTest) and in paircheck_test.go.
const (
	pairSasContext    = "fileapex-pair-sas-v1"
	pairCommitContext = "fileapex-pair-sas-commit-v1"
)

// pairCommit is what the joiner sends first; the host later checks the revealed nonce against it.
func pairCommit(nonceB, joinerPin, joinerID string) string {
	sum := sha256.Sum256([]byte(pairCommitContext + "|" + strings.ToLower(nonceB) + "|" + strings.ToLower(joinerPin) + "|" + joinerID))
	return hex.EncodeToString(sum[:])
}

// pairSasCode is the six digits, shown as "123 456".
func pairSasCode(hostPin, joinerPin, hostID, joinerID, nonceA, nonceB string) string {
	sum := sha256.Sum256([]byte(pairSasContext + "|" + strings.ToLower(hostPin) + "|" + strings.ToLower(joinerPin) + "|" +
		hostID + "|" + joinerID + "|" + strings.ToLower(nonceA) + "|" + strings.ToLower(nonceB)))
	number := binary.BigEndian.Uint32(sum[:4]) % 1_000_000
	text := fmt.Sprintf("%06d", number)
	return text[:3] + " " + text[3:]
}
