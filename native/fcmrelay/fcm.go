package main

import (
	"bytes"
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

// ServiceAccount is the part of the Firebase admin JSON the relay needs.
type ServiceAccount struct {
	ProjectID   string `json:"project_id"`
	ClientEmail string `json:"client_email"`
	PrivateKey  string `json:"private_key"`
	TokenURI    string `json:"token_uri"`
}

var (
	errUnregistered = errors.New("target token is not registered")
	errFCM          = errors.New("fcm send failed")
)

// Sender sends data messages through FCM HTTP v1 with the service account key.
type Sender struct {
	Account ServiceAccount
	HTTP    *http.Client
	SendURL string
	Now     func() time.Time

	mu      sync.Mutex
	key     *rsa.PrivateKey
	token   string
	expires time.Time
}

func (s *Sender) Send(ctx context.Context, target string, data map[string]string) error {
	accessToken, err := s.accessToken(ctx)
	if err != nil {
		return fmt.Errorf("%w: access token: %v", errFCM, err)
	}
	payload, _ := json.Marshal(map[string]any{
		"message": map[string]any{
			"token":   target,
			"data":    data,
			"android": map[string]string{"priority": "HIGH"},
		},
	})
	request, _ := http.NewRequestWithContext(ctx, http.MethodPost, s.SendURL, bytes.NewReader(payload))
	request.Header.Set("Authorization", "Bearer "+accessToken)
	request.Header.Set("Content-Type", "application/json")
	response, err := s.HTTP.Do(request)
	if err != nil {
		return fmt.Errorf("%w: %v", errFCM, err)
	}
	defer response.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(response.Body, 4096))
	switch {
	case response.StatusCode/100 == 2:
		return nil
	case response.StatusCode == http.StatusNotFound || strings.Contains(string(body), "UNREGISTERED"):
		return errUnregistered
	default:
		return fmt.Errorf("%w: status %d", errFCM, response.StatusCode)
	}
}

func (s *Sender) accessToken(ctx context.Context) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.token != "" && s.Now().Add(time.Minute).Before(s.expires) {
		return s.token, nil
	}
	if s.key == nil {
		block, _ := pem.Decode([]byte(s.Account.PrivateKey))
		if block == nil {
			return "", errors.New("service account key is not PEM")
		}
		parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes)
		if err != nil {
			return "", err
		}
		key, ok := parsed.(*rsa.PrivateKey)
		if !ok {
			return "", errors.New("service account key is not RSA")
		}
		s.key = key
	}
	now := s.Now()
	claims, _ := json.Marshal(map[string]any{
		"iss":   s.Account.ClientEmail,
		"scope": "https://www.googleapis.com/auth/firebase.messaging",
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
