package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
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

	once   sync.Once
	tokens *AccessTokens
}

func (s *Sender) Send(ctx context.Context, target string, data map[string]string) error {
	accessToken, err := s.accessTokens().Token(ctx)
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

func (s *Sender) accessTokens() *AccessTokens {
	s.once.Do(func() {
		s.tokens = &AccessTokens{Account: s.Account, HTTP: s.HTTP, Now: s.Now, Scope: "https://www.googleapis.com/auth/firebase.messaging"}
	})
	return s.tokens
}
