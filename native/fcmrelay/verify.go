package main

import (
	"crypto"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

// KeySource returns the public key Firebase signs ID tokens with for a key id.
type KeySource interface {
	Key(kid string) (*rsa.PublicKey, error)
}

// Verifier checks Firebase Auth ID tokens: RS256 signature, issuer, audience, expiry and subject.
type Verifier struct {
	ProjectID string
	Keys      KeySource
	Now       func() time.Time
}

var errBadToken = errors.New("invalid id token")

// Verify returns the Firebase user id (the `sub` claim) of a valid token.
func (v *Verifier) Verify(token string) (string, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return "", errBadToken
	}
	var header struct {
		Alg string `json:"alg"`
		Kid string `json:"kid"`
	}
	if err := decodePart(parts[0], &header); err != nil || header.Alg != "RS256" || header.Kid == "" {
		return "", errBadToken
	}
	var claims struct {
		Iss string `json:"iss"`
		Aud string `json:"aud"`
		Sub string `json:"sub"`
		Exp int64  `json:"exp"`
		Iat int64  `json:"iat"`
	}
	if err := decodePart(parts[1], &claims); err != nil {
		return "", errBadToken
	}
	signature, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		return "", errBadToken
	}
	key, err := v.Keys.Key(header.Kid)
	if err != nil || key == nil {
		return "", errBadToken
	}
	digest := sha256.Sum256([]byte(parts[0] + "." + parts[1]))
	if rsa.VerifyPKCS1v15(key, crypto.SHA256, digest[:], signature) != nil {
		return "", errBadToken
	}
	now := v.Now()
	switch {
	case claims.Aud != v.ProjectID,
		claims.Iss != "https://securetoken.google.com/"+v.ProjectID,
		claims.Sub == "" || len(claims.Sub) > 128,
		claims.Exp <= now.Unix(),
		claims.Iat > now.Add(time.Minute).Unix():
		return "", errBadToken
	}
	return claims.Sub, nil
}

func decodePart(part string, out any) error {
	raw, err := base64.RawURLEncoding.DecodeString(part)
	if err != nil {
		return err
	}
	return json.Unmarshal(raw, out)
}

// googleKeys fetches Firebase's signing certificates and keeps them for as long as Google says.
type googleKeys struct {
	URL  string
	HTTP *http.Client
	Now  func() time.Time

	mu      sync.Mutex
	keys    map[string]*rsa.PublicKey
	expires time.Time
}

const firebaseCertsURL = "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com"

func (g *googleKeys) Key(kid string) (*rsa.PublicKey, error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if key, ok := g.keys[kid]; ok && g.Now().Before(g.expires) {
		return key, nil
	}
	if err := g.refresh(); err != nil {
		return nil, err
	}
	key, ok := g.keys[kid]
	if !ok {
		return nil, fmt.Errorf("unknown key id")
	}
	return key, nil
}

func (g *googleKeys) refresh() error {
	response, err := g.HTTP.Get(g.URL)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("certificate fetch: %s", response.Status)
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		return err
	}
	var certs map[string]string
	if err := json.Unmarshal(body, &certs); err != nil {
		return err
	}
	keys := map[string]*rsa.PublicKey{}
	for kid, pemText := range certs {
		block, _ := pem.Decode([]byte(pemText))
		if block == nil {
			continue
		}
		cert, err := x509.ParseCertificate(block.Bytes)
		if err != nil {
			continue
		}
		if key, ok := cert.PublicKey.(*rsa.PublicKey); ok {
			keys[kid] = key
		}
	}
	if len(keys) == 0 {
		return fmt.Errorf("no usable certificates")
	}
	maxAge := time.Hour
	for _, directive := range strings.Split(response.Header.Get("Cache-Control"), ",") {
		if value, found := strings.CutPrefix(strings.TrimSpace(directive), "max-age="); found {
			if seconds, err := strconv.Atoi(value); err == nil && seconds > 0 {
				maxAge = time.Duration(seconds) * time.Second
			}
		}
	}
	g.keys = keys
	g.expires = g.Now().Add(maxAge)
	return nil
}
