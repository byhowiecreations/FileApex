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
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

var testNow = time.Date(2026, 10, 21, 12, 0, 0, 0, time.UTC)

func fixedNow() time.Time { return testNow }

type fixedKeys map[string]*rsa.PublicKey

func (f fixedKeys) Key(kid string) (*rsa.PublicKey, error) {
	if key, ok := f[kid]; ok {
		return key, nil
	}
	return nil, errors.New("unknown")
}

func newKey(t *testing.T) *rsa.PrivateKey {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	return key
}

func signedToken(t *testing.T, key *rsa.PrivateKey, kid string, claims map[string]any) string {
	t.Helper()
	header, _ := json.Marshal(map[string]string{"alg": "RS256", "kid": kid})
	body, _ := json.Marshal(claims)
	input := base64.RawURLEncoding.EncodeToString(header) + "." + base64.RawURLEncoding.EncodeToString(body)
	digest := sha256.Sum256([]byte(input))
	signature, err := rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, digest[:])
	if err != nil {
		t.Fatal(err)
	}
	return input + "." + base64.RawURLEncoding.EncodeToString(signature)
}

func goodClaims() map[string]any {
	return map[string]any{
		"iss": "https://securetoken.google.com/proj", "aud": "proj", "sub": "user-1",
		"iat": testNow.Add(-time.Minute).Unix(), "exp": testNow.Add(time.Hour).Unix(),
	}
}

func TestVerifierAcceptsAGoodTokenAndRefusesEverythingElse(t *testing.T) {
	key := newKey(t)
	verifier := &Verifier{ProjectID: "proj", Keys: fixedKeys{"k1": &key.PublicKey}, Now: fixedNow}
	uid, err := verifier.Verify(signedToken(t, key, "k1", goodClaims()))
	if err != nil || uid != "user-1" {
		t.Fatalf("good token: %q %v", uid, err)
	}
	mutate := func(field string, value any) string {
		claims := goodClaims()
		claims[field] = value
		return signedToken(t, key, "k1", claims)
	}
	bad := map[string]string{
		"wrong audience": mutate("aud", "other"),
		"wrong issuer":   mutate("iss", "https://securetoken.google.com/other"),
		"expired":        mutate("exp", testNow.Add(-time.Second).Unix()),
		"issued ahead":   mutate("iat", testNow.Add(time.Hour).Unix()),
		"no subject":     mutate("sub", ""),
		"unknown kid":    signedToken(t, key, "k2", goodClaims()),
		"other signer":   signedToken(t, newKey(t), "k1", goodClaims()),
		"not a jwt":      "abc",
		"empty":          "",
	}
	for name, token := range bad {
		if _, err := verifier.Verify(token); err == nil {
			t.Errorf("%s was accepted", name)
		}
	}
	none := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"none","kid":"k1"}`)) + "." +
		base64.RawURLEncoding.EncodeToString([]byte(`{"aud":"proj"}`)) + "."
	if _, err := verifier.Verify(none); err == nil {
		t.Error("alg none was accepted")
	}
}

type fakeSender struct {
	calls  int32
	target string
	data   map[string]string
	err    error
}

func (f *fakeSender) Send(_ context.Context, target string, data map[string]string) error {
	atomic.AddInt32(&f.calls, 1)
	f.target, f.data = target, data
	return f.err
}

func newTestServer(t *testing.T, sender *fakeSender, perUserBurst int) (*httptest.Server, string) {
	t.Helper()
	key := newKey(t)
	server := &Server{
		Verifier: &Verifier{ProjectID: "proj", Keys: fixedKeys{"k1": &key.PublicKey}, Now: fixedNow},
		Sender:   sender,
		PerUser:  newLimiter(60, perUserBurst, fixedNow),
		Global:   newLimiter(6000, 1000, fixedNow),
	}
	return httptest.NewServer(server.Handler()), signedToken(t, key, "k1", goodClaims())
}

func post(t *testing.T, url, token, body string) *http.Response {
	t.Helper()
	request, _ := http.NewRequest(http.MethodPost, url+"/v1/wake", strings.NewReader(body))
	if token != "" {
		request.Header.Set("Authorization", "Bearer "+token)
	}
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { response.Body.Close() })
	return response
}

const goodBody = `{"token":"fcm-target-token-0123456789","data":{"type":"presence_wake","sourceDeviceId":"d1","epochMs":"1"}}`

func TestWakeForwardsAnAuthenticatedRequest(t *testing.T) {
	sender := &fakeSender{}
	srv, token := newTestServer(t, sender, 5)
	defer srv.Close()
	if status := post(t, srv.URL, token, goodBody).StatusCode; status != 200 {
		t.Fatalf("status %d", status)
	}
	if sender.target != "fcm-target-token-0123456789" || sender.data["type"] != "presence_wake" || sender.data["sourceDeviceId"] != "d1" {
		t.Fatalf("forwarded %q %v", sender.target, sender.data)
	}
}

func TestWakeRefusesWhatItShould(t *testing.T) {
	sender := &fakeSender{}
	srv, token := newTestServer(t, sender, 100)
	defer srv.Close()
	big := strings.Repeat("x", 4100)
	cases := map[string]struct {
		token, body string
		status      int
	}{
		"no token":        {"", goodBody, 401},
		"bad token":       {"nope", goodBody, 401},
		"unknown type":    {token, `{"token":"fcm-target-token-0123456789","data":{"type":"anything_else"}}`, 400},
		"no type":         {token, `{"token":"fcm-target-token-0123456789","data":{}}`, 400},
		"short target":    {token, `{"token":"x","data":{"type":"presence_wake"}}`, 400},
		"reserved key":    {token, `{"token":"fcm-target-token-0123456789","data":{"type":"presence_wake","google_x":"1"}}`, 400},
		"payload too big": {token, `{"token":"fcm-target-token-0123456789","data":{"type":"note_inline","content":"` + big + `"}}`, 400},
		"not json":        {token, `hello`, 400},
	}
	for name, c := range cases {
		if status := post(t, srv.URL, c.token, c.body).StatusCode; status != c.status {
			t.Errorf("%s: status %d, want %d", name, status, c.status)
		}
	}
	if sender.calls != 0 {
		t.Fatalf("a refused request reached FCM %d times", sender.calls)
	}
	response, _ := http.Get(srv.URL + "/v1/wake")
	if response.StatusCode != 405 {
		t.Errorf("GET answered %d", response.StatusCode)
	}
}

func TestWakeRateLimitsPerUser(t *testing.T) {
	sender := &fakeSender{}
	srv, token := newTestServer(t, sender, 3)
	defer srv.Close()
	for i := 0; i < 3; i++ {
		if status := post(t, srv.URL, token, goodBody).StatusCode; status != 200 {
			t.Fatalf("request %d: %d", i, status)
		}
	}
	response := post(t, srv.URL, token, goodBody)
	if response.StatusCode != 429 || response.Header.Get("Retry-After") == "" {
		t.Fatalf("fourth request: %d retry-after %q", response.StatusCode, response.Header.Get("Retry-After"))
	}
}

func TestWakeMapsFcmFailures(t *testing.T) {
	sender := &fakeSender{err: errUnregistered}
	srv, token := newTestServer(t, sender, 10)
	defer srv.Close()
	if status := post(t, srv.URL, token, goodBody).StatusCode; status != 410 {
		t.Errorf("unregistered: %d", status)
	}
	sender.err = errFCM
	if status := post(t, srv.URL, token, goodBody).StatusCode; status != 502 {
		t.Errorf("fcm failure: %d", status)
	}
}

func TestLimiterRefills(t *testing.T) {
	now := testNow
	l := newLimiter(60, 2, func() time.Time { return now })
	l.allow("a")
	l.allow("a")
	if ok, wait := l.allow("a"); ok || wait <= 0 {
		t.Fatalf("third call: %v %v", ok, wait)
	}
	now = now.Add(2 * time.Second)
	if ok, _ := l.allow("a"); !ok {
		t.Fatal("no refill after two seconds")
	}
	if ok, _ := l.allow("b"); !ok {
		t.Fatal("another user was limited")
	}
}

func TestSenderGetsOneAccessTokenAndShapesTheMessage(t *testing.T) {
	signer := newKey(t)
	pkcs8, _ := x509.MarshalPKCS8PrivateKey(signer)
	var tokenCalls int32
	var sentBody map[string]any
	var sentAuth string
	google := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/token":
			atomic.AddInt32(&tokenCalls, 1)
			r.ParseForm()
			if r.Form.Get("grant_type") != "urn:ietf:params:oauth:grant-type:jwt-bearer" || strings.Count(r.Form.Get("assertion"), ".") != 2 {
				w.WriteHeader(400)
				return
			}
			w.Write([]byte(`{"access_token":"at-1","expires_in":3600}`))
		case "/send":
			sentAuth = r.Header.Get("Authorization")
			json.NewDecoder(r.Body).Decode(&sentBody)
			if strings.Contains(sentBody["message"].(map[string]any)["token"].(string), "gone") {
				w.WriteHeader(404)
				return
			}
			w.Write([]byte(`{"name":"m"}`))
		}
	}))
	defer google.Close()
	sender := &Sender{
		Account: ServiceAccount{
			ProjectID: "proj", ClientEmail: "svc@proj.iam", TokenURI: google.URL + "/token",
			PrivateKey: string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: pkcs8})),
		},
		HTTP: google.Client(), SendURL: google.URL + "/send", Now: fixedNow,
	}
	for i := 0; i < 2; i++ {
		if err := sender.Send(context.Background(), "target-token", map[string]string{"type": "presence_wake"}); err != nil {
			t.Fatal(err)
		}
	}
	if tokenCalls != 1 {
		t.Errorf("token endpoint called %d times", tokenCalls)
	}
	message := sentBody["message"].(map[string]any)
	if sentAuth != "Bearer at-1" || message["token"] != "target-token" ||
		message["data"].(map[string]any)["type"] != "presence_wake" ||
		message["android"].(map[string]any)["priority"] != "HIGH" {
		t.Errorf("message shape: %v auth %q", sentBody, sentAuth)
	}
	if err := sender.Send(context.Background(), "gone-token", map[string]string{"type": "presence_wake"}); !errors.Is(err, errUnregistered) {
		t.Errorf("404 mapped to %v", err)
	}
}
