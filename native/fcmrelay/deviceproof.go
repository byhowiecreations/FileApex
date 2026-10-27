package main

import (
	"crypto/ecdh"
	"crypto/hkdf"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	proofRelayID   = "fileapex-relay"
	proofHKDFInfo  = "FileApex-Hello-v1"
	proofMaxSkew   = 2 * time.Minute
	proofNonceKeep = 5 * time.Minute
	maxNonces      = 10000
)

// ProofKey is the relay's own X25519 key. A device proves it holds the private half of the key in its cloud
// record with an HMAC under the shared secret, the same construction as ClipboardCrypto.authenticate.
type ProofKey struct {
	private *ecdh.PrivateKey
	Now     func() time.Time

	mu    sync.Mutex
	nonce map[string]time.Time
}

func newProofKey(now func() time.Time) (*ProofKey, error) {
	private, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}
	return &ProofKey{private: private, Now: now, nonce: map[string]time.Time{}}, nil
}

func (p *ProofKey) PublicBase64() string {
	return base64.StdEncoding.EncodeToString(p.private.PublicKey().Bytes())
}

func proofMessage(deviceID string, ts int64, nonce string) []byte {
	return []byte("FileApex-Enrol-v1|" + deviceID + "|" + strconv.FormatInt(ts, 10) + "|" + nonce)
}

// Verify checks the tag over (deviceID, ts, nonce) and burns the nonce, so a captured request cannot be replayed.
func (p *ProofKey) Verify(devicePublicBase64, deviceID string, ts int64, nonce, tagBase64 string) bool {
	if len(nonce) < 16 || len(nonce) > 64 {
		return false
	}
	now := p.Now()
	skew := now.Sub(time.Unix(ts, 0))
	if skew > proofMaxSkew || skew < -proofMaxSkew {
		return false
	}
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(devicePublicBase64))
	if err != nil {
		return false
	}
	public, err := ecdh.X25519().NewPublicKey(raw)
	if err != nil {
		return false
	}
	tag, err := base64.StdEncoding.DecodeString(tagBase64)
	if err != nil {
		return false
	}
	shared, err := p.private.ECDH(public)
	if err != nil {
		return false
	}
	ids := []string{strings.TrimSpace(deviceID), proofRelayID}
	sort.Strings(ids)
	key, err := hkdf.Key(sha256.New, shared, []byte(strings.Join(ids, "|")), proofHKDFInfo, 32)
	if err != nil {
		return false
	}
	mac := hmac.New(sha256.New, key)
	mac.Write(proofMessage(deviceID, ts, nonce))
	if !hmac.Equal(mac.Sum(nil), tag) {
		return false
	}
	return p.burn(nonce, now)
}

func (p *ProofKey) burn(nonce string, now time.Time) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	for seen, expires := range p.nonce {
		if !expires.After(now) {
			delete(p.nonce, seen)
		}
	}
	if _, used := p.nonce[nonce]; used || len(p.nonce) >= maxNonces {
		return false
	}
	p.nonce[nonce] = now.Add(proofNonceKeep)
	return true
}

// keyDigestHex is the digest IdentityBinding.kt builds the device id and the stored key hash from.
func keyDigestHex(publicBase64 string) (string, bool) {
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(publicBase64))
	if err != nil || len(raw) != 32 {
		return "", false
	}
	sum := sha256.Sum256(raw)
	return hex.EncodeToString(sum[:]), true
}
