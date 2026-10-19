package main

import (
	"crypto/sha256"
	"encoding/binary"
	"fmt"
	"strings"
)

// The short code both screens show while two devices pair. It must equal the app's PairingCheckCode: the same
// vectors are asserted in the Kotlin tests (PairingCheckCodeTest) and in paircheck_test.go.
const pairCheckContext = "fileapex-pair-check-v1"

// checkKey is a device's key for the check: its TLS pin, or its id when it has none.
func checkKey(pin, deviceID string) string {
	pin = strings.ToLower(strings.TrimSpace(pin))
	if pin == "" {
		return strings.TrimSpace(deviceID)
	}
	return pin
}

// pairCheckCode takes the host's key first, then the joiner's, then the one-time pairing code.
func pairCheckCode(hostKey, joinerKey, pairingCode string) string {
	var digits strings.Builder
	for _, r := range pairingCode {
		if r >= '0' && r <= '9' {
			digits.WriteRune(r)
		}
	}
	sum := sha256.Sum256([]byte(pairCheckContext + "|" + hostKey + "|" + joinerKey + "|" + digits.String()))
	number := binary.BigEndian.Uint32(sum[:4]) % 1_000_000
	text := fmt.Sprintf("%06d", number)
	return text[:3] + " " + text[3:]
}
