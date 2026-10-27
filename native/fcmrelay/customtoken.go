package main

import (
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"sync"
	"time"
)

const identityAudience = "https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit"

// CustomTokens mints Firebase custom tokens signed with the service account key. A client trades one for a
// Firebase session with signInWithCustomToken; the claims survive token refresh.
type CustomTokens struct {
	Account ServiceAccount
	Now     func() time.Time

	once sync.Once
	key  *rsa.PrivateKey
	err  error
}

func (c *CustomTokens) Mint(uid string, claims map[string]any) (string, error) {
	if uid == "" || len(uid) > 128 {
		return "", errors.New("custom token uid must be 1 to 128 characters")
	}
	c.once.Do(func() { c.key, c.err = parseServiceKey(c.Account.PrivateKey) })
	if c.err != nil {
		return "", c.err
	}
	now := c.Now()
	body, _ := json.Marshal(map[string]any{
		"iss":    c.Account.ClientEmail,
		"sub":    c.Account.ClientEmail,
		"aud":    identityAudience,
		"iat":    now.Unix(),
		"exp":    now.Add(time.Hour).Unix(),
		"uid":    uid,
		"claims": claims,
	})
	input := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"RS256","typ":"JWT"}`)) + "." + base64.RawURLEncoding.EncodeToString(body)
	digest := sha256.Sum256([]byte(input))
	signature, err := rsa.SignPKCS1v15(rand.Reader, c.key, crypto.SHA256, digest[:])
	if err != nil {
		return "", err
	}
	return input + "." + base64.RawURLEncoding.EncodeToString(signature), nil
}
