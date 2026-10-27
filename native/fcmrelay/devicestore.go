package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// State is the per-account allow-list. The Firestore rules do not read it (Variant B); only the relay does.
type State struct {
	Allowed    map[string]string  `json:"allowed"`
	Since      map[string]int64   `json:"since"`
	Denied     map[string]Version `json:"denied"`
	SeedCutoff time.Time          `json:"seedCutoff"`
}

// Version is the cluster version of a removal. A denial written as `true` by an earlier build counts as 0.
type Version int64

func (v *Version) UnmarshalJSON(raw []byte) error {
	if string(raw) == "true" {
		*v = 0
		return nil
	}
	var n int64
	if err := json.Unmarshal(raw, &n); err != nil {
		return err
	}
	*v = Version(n)
	return nil
}

// DeviceRecord is the part of users/{owner}/devices/{id} the relay needs.
type DeviceRecord struct {
	PublicKeyHash      string
	ClipboardPublicKey string
	Created            time.Time
}

var errConflict = errors.New("state changed under us")

type Store interface {
	Device(ctx context.Context, owner, deviceID string) (*DeviceRecord, error)
	LoadState(ctx context.Context, owner string) (state *State, version string, err error)
	SaveState(ctx context.Context, owner string, state *State, version string) error
}

// Identity switches the Firebase user that belongs to one device on or off.
type Identity interface {
	SetEnabled(ctx context.Context, uid string, enabled bool) error
}

// Admin talks to Firestore and Identity Toolkit with the service account.
type Admin struct {
	Project      string
	Tokens       *AccessTokens
	HTTP         *http.Client
	FirestoreURL string
	IdentityURL  string
}

func newAdmin(account ServiceAccount, client *http.Client, now func() time.Time) *Admin {
	return &Admin{
		Project: account.ProjectID,
		Tokens: &AccessTokens{
			Account: account, HTTP: client, Now: now,
			Scope: "https://www.googleapis.com/auth/cloud-platform",
		},
		HTTP:         client,
		FirestoreURL: "https://firestore.googleapis.com/v1",
		IdentityURL:  "https://identitytoolkit.googleapis.com/v1",
	}
}

func (a *Admin) documentURL(path ...string) string {
	escaped := make([]string, len(path))
	for i, part := range path {
		escaped[i] = url.PathEscape(part)
	}
	return a.FirestoreURL + "/projects/" + url.PathEscape(a.Project) + "/databases/(default)/documents/" + strings.Join(escaped, "/")
}

func (a *Admin) do(ctx context.Context, method, target string, body any) (int, []byte, error) {
	token, err := a.Tokens.Token(ctx)
	if err != nil {
		return 0, nil, err
	}
	var reader io.Reader
	if body != nil {
		raw, _ := json.Marshal(body)
		reader = bytes.NewReader(raw)
	}
	request, err := http.NewRequestWithContext(ctx, method, target, reader)
	if err != nil {
		return 0, nil, err
	}
	request.Header.Set("Authorization", "Bearer "+token)
	request.Header.Set("Content-Type", "application/json")
	response, err := a.HTTP.Do(request)
	if err != nil {
		return 0, nil, err
	}
	defer response.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	return response.StatusCode, raw, err
}

type firestoreDocument struct {
	Fields     map[string]map[string]any `json:"fields"`
	CreateTime string                    `json:"createTime"`
	UpdateTime string                    `json:"updateTime"`
}

func (d firestoreDocument) text(field string) string {
	value, _ := d.Fields[field]["stringValue"].(string)
	return value
}

func (a *Admin) Device(ctx context.Context, owner, deviceID string) (*DeviceRecord, error) {
	status, raw, err := a.do(ctx, http.MethodGet, a.documentURL("users", owner, "devices", deviceID), nil)
	if err != nil {
		return nil, err
	}
	if status == http.StatusNotFound {
		return nil, nil
	}
	if status != http.StatusOK {
		return nil, fmt.Errorf("device record read answered %d", status)
	}
	var doc firestoreDocument
	if err := json.Unmarshal(raw, &doc); err != nil {
		return nil, err
	}
	created, err := time.Parse(time.RFC3339Nano, doc.CreateTime)
	if err != nil {
		return nil, err
	}
	return &DeviceRecord{PublicKeyHash: doc.text("publicKeyHash"), ClipboardPublicKey: doc.text("clipboardPublicKey"), Created: created}, nil
}

func (a *Admin) LoadState(ctx context.Context, owner string) (*State, string, error) {
	status, raw, err := a.do(ctx, http.MethodGet, a.documentURL("users", owner, "security", "state"), nil)
	if err != nil {
		return nil, "", err
	}
	if status == http.StatusNotFound {
		return nil, "", nil
	}
	if status != http.StatusOK {
		return nil, "", fmt.Errorf("state read answered %d", status)
	}
	var doc firestoreDocument
	if err := json.Unmarshal(raw, &doc); err != nil {
		return nil, "", err
	}
	var state State
	if err := json.Unmarshal([]byte(doc.text("json")), &state); err != nil {
		return nil, "", err
	}
	return &state, doc.UpdateTime, nil
}

func (a *Admin) SaveState(ctx context.Context, owner string, state *State, version string) error {
	encoded, _ := json.Marshal(state)
	target := a.documentURL("users", owner, "security", "state") + "?updateMask.fieldPaths=json"
	if version == "" {
		target += "&currentDocument.exists=false"
	} else {
		target += "&currentDocument.updateTime=" + url.QueryEscape(version)
	}
	body := map[string]any{"fields": map[string]any{"json": map[string]string{"stringValue": string(encoded)}}}
	status, raw, err := a.do(ctx, http.MethodPatch, target, body)
	switch {
	case err != nil:
		return err
	case status == http.StatusOK:
		return nil
	case status == http.StatusConflict || strings.Contains(string(raw), "FAILED_PRECONDITION"):
		return errConflict
	default:
		return fmt.Errorf("state write answered %d", status)
	}
}

// SetEnabled flips disableUser on the device's Firebase user. A user that never signed in does not exist,
// which is the same outcome as disabled, so USER_NOT_FOUND counts as done.
func (a *Admin) SetEnabled(ctx context.Context, uid string, enabled bool) error {
	target := a.IdentityURL + "/projects/" + url.PathEscape(a.Project) + "/accounts:update"
	status, raw, err := a.do(ctx, http.MethodPost, target, map[string]any{"localId": uid, "disableUser": !enabled})
	switch {
	case err != nil:
		return err
	case status == http.StatusOK:
		return nil
	case strings.Contains(string(raw), "USER_NOT_FOUND"):
		return nil
	default:
		return fmt.Errorf("identity update answered %d", status)
	}
}
