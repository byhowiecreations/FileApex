package main

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// allowedTypes are the wake types FileApex sends (FcmWakeProtocol.kt); anything else is refused.
var allowedTypes = map[string]bool{
	"presence_wake": true, "diagnostics_request": true, "note_inline": true, "note_sync": true,
	"note_delete": true, "retract_message": true, "drive_relay": true, "clipboard_share": true,
	"clipboard_opt_in_request": true, "clipboard_ack": true,
}

const (
	maxBodyBytes    = 16 << 10
	maxPayloadBytes = 4000 // FCM allows 4096 bytes of data
	maxDataKeys     = 40
)

var dataKey = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_]{0,63}$`)

type Server struct {
	Verifier *Verifier
	Sender   interface {
		Send(ctx context.Context, target string, data map[string]string) error
	}
	PerUser *limiter
	Global  *limiter
}

type wakeRequest struct {
	Token string            `json:"token"`
	Data  map[string]string `json:"data"`
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte("ok")) })
	mux.HandleFunc("/v1/wake", s.wake)
	return mux
}

func (s *Server) wake(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		fail(w, http.StatusMethodNotAllowed, "post only")
		return
	}
	bearer, found := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	if !found {
		fail(w, http.StatusUnauthorized, "missing token")
		return
	}
	uid, err := s.Verifier.Verify(strings.TrimSpace(bearer))
	if err != nil {
		fail(w, http.StatusUnauthorized, "invalid token")
		return
	}
	if ok, wait := s.PerUser.allow(uid); !ok {
		tooMany(w, wait)
		return
	}
	if ok, wait := s.Global.allow("all"); !ok {
		tooMany(w, wait)
		return
	}
	var req wakeRequest
	body, err := io.ReadAll(io.LimitReader(r.Body, maxBodyBytes+1))
	if err != nil || len(body) > maxBodyBytes || json.Unmarshal(body, &req) != nil {
		fail(w, http.StatusBadRequest, "bad body")
		return
	}
	if reason := validate(req); reason != "" {
		fail(w, http.StatusBadRequest, reason)
		return
	}
	switch err := s.Sender.Send(r.Context(), req.Token, req.Data); {
	case err == nil:
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"ok":true}`))
	case errors.Is(err, errUnregistered):
		fail(w, http.StatusGone, "target is not registered")
	default:
		fail(w, http.StatusBadGateway, "push failed")
	}
}

func validate(req wakeRequest) string {
	if len(req.Token) < 20 || len(req.Token) > 4096 || strings.ContainsAny(req.Token, " \t\r\n") {
		return "bad target token"
	}
	if !allowedTypes[req.Data["type"]] {
		return "type not allowed"
	}
	if len(req.Data) > maxDataKeys {
		return "too many fields"
	}
	size := 0
	for key, value := range req.Data {
		lower := strings.ToLower(key)
		if !dataKey.MatchString(key) || lower == "from" || strings.HasPrefix(lower, "google") || strings.HasPrefix(lower, "gcm") {
			return "bad field name"
		}
		size += len(key) + len(value)
	}
	if size > maxPayloadBytes {
		return "payload too large"
	}
	return ""
}

func tooMany(w http.ResponseWriter, wait time.Duration) {
	w.Header().Set("Retry-After", strconv.Itoa(int(wait.Seconds())+1))
	fail(w, http.StatusTooManyRequests, "slow down")
}

func fail(w http.ResponseWriter, status int, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(map[string]string{"error": message})
}
