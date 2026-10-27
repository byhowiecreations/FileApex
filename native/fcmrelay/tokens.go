package main

import (
	"context"
	"crypto"
	"crypto/rand"
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
	"net/url"
	"strings"
	"sync"
	"time"
)

// AccessTokens trades the service account key for a Google OAuth access token with one scope and caches it.
type AccessTokens struct {
	Account ServiceAccount
	HTTP    *http.Client
	Now     func() time.Time
	Scope   string

	mu      sync.Mutex
	key     *rsa.PrivateKey
	token   string
	expires time.Time
}

func (s *AccessTokens) Token(ctx context.Context) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.token != "" && s.Now().Add(time.Minute).Before(s.expires) {
		return s.token, nil
	}
	if s.key == nil {
		key, err := parseServiceKey(s.Account.PrivateKey)
		if err != nil {
			return "", err
		}
		s.key = key
	}
	now := s.Now()
	claims, _ := json.Marshal(map[string]any{
		"iss":   s.Account.ClientEmail,
		"scope": s.Scope,
		"aud":   s.Account.TokenURI,
		"iat":   now.Unix(),
		"exp":   now.Add(time.Hour).Unix(),
	})
	signingInput := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"RS256","typ":"JWT"}`)) + "." +
		base64.RawURLEncoding.EncodeToString(claims)
	digest := sha256.Sum256([]byte(signingInput))
	signature, err := rsa.SignPKCS1v15(rand.Reader, s.key, crypto.SHA256, digest[:])
	if err != nil {
		return "", err
	}
	form := url.Values{
		"grant_type": {"urn:ietf:params:oauth:grant-type:jwt-bearer"},
		"assertion":  {signingInput + "." + base64.RawURLEncoding.EncodeToString(signature)},
	}
	request, _ := http.NewRequestWithContext(ctx, http.MethodPost, s.Account.TokenURI, strings.NewReader(form.Encode()))
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	response, err := s.HTTP.Do(request)
	if err != nil {
		return "", err
	}
	defer response.Body.Close()
	var reply struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if err := json.NewDecoder(io.LimitReader(response.Body, 1<<16)).Decode(&reply); err != nil || response.StatusCode != http.StatusOK || reply.AccessToken == "" {
		return "", fmt.Errorf("token endpoint answered %d", response.StatusCode)
	}
	s.token = reply.AccessToken
	s.expires = now.Add(time.Duration(reply.ExpiresIn) * time.Second)
	return s.token, nil
}

func parseServiceKey(privateKey string) (*rsa.PrivateKey, error) {
	block, _ := pem.Decode([]byte(privateKey))
	if block == nil {
		return nil, errors.New("service account key is not PEM")
	}
	parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, err
	}
	key, ok := parsed.(*rsa.PrivateKey)
	if !ok {
		return nil, errors.New("service account key is not RSA")
	}
	return key, nil
}
