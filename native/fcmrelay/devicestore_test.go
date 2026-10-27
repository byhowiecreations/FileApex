package main

import (
	"context"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestAdminSpeaksFirestoreAndIdentityToolkit(t *testing.T) {
	var saved, identityBody, savedQuery string
	var stateStored string
	google := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/token":
			w.Write([]byte(`{"access_token":"at-1","expires_in":3600}`))
		case r.URL.Path == "/v1/projects/proj/databases/(default)/documents/users/user1/devices/fx-abc":
			w.Write([]byte(`{"createTime":"2026-10-01T10:00:00.123456Z","fields":{"publicKeyHash":{"stringValue":"h1"},"clipboardPublicKey":{"stringValue":"k1"}}}`))
		case r.URL.Path == "/v1/projects/proj/databases/(default)/documents/users/user1/security/state" && r.Method == http.MethodGet:
			if stateStored == "" {
				w.WriteHeader(404)
				return
			}
			doc, _ := json.Marshal(map[string]any{"updateTime": "2026-10-02T10:00:00Z", "fields": map[string]any{"json": map[string]string{"stringValue": stateStored}}})
			w.Write(doc)
		case r.URL.Path == "/v1/projects/proj/databases/(default)/documents/users/user1/security/state" && r.Method == http.MethodPatch:
			raw, _ := io.ReadAll(r.Body)
			saved, savedQuery = string(raw), r.URL.RawQuery
			var body struct {
				Fields struct {
					JSON struct {
						StringValue string `json:"stringValue"`
					} `json:"json"`
				} `json:"fields"`
			}
			json.Unmarshal(raw, &body)
			stateStored = body.Fields.JSON.StringValue
			w.Write([]byte(`{}`))
		case r.URL.Path == "/v1/projects/proj/accounts:update":
			raw, _ := io.ReadAll(r.Body)
			identityBody = string(raw)
			if strings.Contains(identityBody, "missing") {
				w.WriteHeader(400)
				w.Write([]byte(`{"error":{"message":"USER_NOT_FOUND"}}`))
				return
			}
			w.Write([]byte(`{}`))
		default:
			w.WriteHeader(500)
		}
	}))
	defer google.Close()
	svc := newKey(t)
	pkcs8, _ := x509.MarshalPKCS8PrivateKey(svc)
	account := ServiceAccount{ProjectID: "proj", ClientEmail: "svc@proj.iam", TokenURI: google.URL + "/token", PrivateKey: string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: pkcs8}))}
	admin := newAdmin(account, google.Client(), fixedNow)
	admin.FirestoreURL, admin.IdentityURL = google.URL+"/v1", google.URL+"/v1"
	ctx := context.Background()

	record, err := admin.Device(ctx, "user1", "fx-abc")
	if err != nil || record == nil || record.PublicKeyHash != "h1" || record.ClipboardPublicKey != "k1" || record.Created.Year() != 2026 {
		t.Fatalf("device: %+v %v", record, err)
	}
	if state, version, err := admin.LoadState(ctx, "user1"); state != nil || version != "" || err != nil {
		t.Fatalf("absent state: %v %q %v", state, version, err)
	}
	cutoff := time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC)
	if err := admin.SaveState(ctx, "user1", &State{Allowed: map[string]string{"fx-abc": "h1"}, SeedCutoff: cutoff}, ""); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(savedQuery, "currentDocument.exists=false") || !strings.Contains(saved, "stringValue") {
		t.Errorf("first save: %q %q", savedQuery, saved)
	}
	state, version, err := admin.LoadState(ctx, "user1")
	if err != nil || version != "2026-10-02T10:00:00Z" || state.Allowed["fx-abc"] != "h1" || !state.SeedCutoff.Equal(cutoff) {
		t.Fatalf("state: %+v %q %v", state, version, err)
	}
	if err := admin.SaveState(ctx, "user1", state, version); err != nil || !strings.Contains(savedQuery, "currentDocument.updateTime=2026-10-02T10%3A00%3A00Z") {
		t.Errorf("update save: %v %q", err, savedQuery)
	}
	if err := admin.SetEnabled(ctx, "user1~fx-abc", false); err != nil || !strings.Contains(identityBody, `"disableUser":true`) || !strings.Contains(identityBody, `"localId":"user1~fx-abc"`) {
		t.Errorf("disable: %v %s", err, identityBody)
	}
	if err := admin.SetEnabled(ctx, "missing", false); err != nil {
		t.Errorf("a user that never signed in must count as done: %v", err)
	}
}
