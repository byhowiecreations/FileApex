package main

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"time"
)

const (
	maxAdmit           = 50
	maxRevoke          = 100
	deviceUIDSeparator = "~"
	derivedIDPrefix    = "fx-"
	derivedIDHexChars  = 32
	keyHashHexChars    = 16
)

var (
	ownerPattern  = regexp.MustCompile(`^[A-Za-z0-9]{1,128}$`)
	devicePattern = regexp.MustCompile(`^[A-Za-z0-9_.-]{1,64}$`)
	hashPattern   = regexp.MustCompile(`^[0-9a-f]{16}$`)
)

// DeviceAdmin keeps the per-account allow-list and hands out one Firebase identity per device (Variant B in
// docs/planning/per-device-firebase-identity-plan.md). The rules never read the list, so a request costs no read.
type DeviceAdmin struct {
	Store    Store
	Identity Identity
	Tokens   *CustomTokens
	Proof    *ProofKey
	Now      func() time.Time

	lockMu sync.Mutex
	locks  map[string]*sync.Mutex
}

func (d *DeviceAdmin) lock(owner string) func() {
	d.lockMu.Lock()
	if d.locks == nil {
		d.locks = map[string]*sync.Mutex{}
	}
	m := d.locks[owner]
	if m == nil {
		m = &sync.Mutex{}
		d.locks[owner] = m
	}
	d.lockMu.Unlock()
	m.Lock()
	return m.Unlock
}

func deviceUID(owner, deviceID string) string { return owner + deviceUIDSeparator + deviceID }

// splitCaller reads a verified token subject: a plain Google uid, or owner~deviceId for an enrolled device.
func splitCaller(sub string) (owner, deviceID string) {
	owner, deviceID, _ = strings.Cut(sub, deviceUIDSeparator)
	return owner, deviceID
}

type enrolRequest struct {
	DeviceID string `json:"deviceId"`
	Ts       int64  `json:"ts"`
	Nonce    string `json:"nonce"`
	Tag      string `json:"tag"`
}

type admitEntry struct {
	DeviceID      string `json:"deviceId"`
	PublicKeyHash string `json:"publicKeyHash"`
	Version       int64  `json:"version"`
}

type revokeEntry struct {
	DeviceID string `json:"deviceId"`
	Version  int64  `json:"version"`
}

type syncRequest struct {
	Admit  []admitEntry  `json:"admit"`
	Revoke []revokeEntry `json:"revoke"`
}

func (s *Server) deviceCaller(w http.ResponseWriter, r *http.Request) (owner, callerDevice string, ok bool) {
	if r.Method != http.MethodPost {
		fail(w, http.StatusMethodNotAllowed, "post only")
		return "", "", false
	}
	bearer, found := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	if !found {
		fail(w, http.StatusUnauthorized, "missing token")
		return "", "", false
	}
	sub, err := s.Verifier.Verify(strings.TrimSpace(bearer))
	if err != nil {
		fail(w, http.StatusUnauthorized, "invalid token")
		return "", "", false
	}
	owner, callerDevice = splitCaller(sub)
	if !ownerPattern.MatchString(owner) || (callerDevice != "" && !devicePattern.MatchString(callerDevice)) {
		fail(w, http.StatusUnauthorized, "invalid token")
		return "", "", false
	}
	if allowed, wait := s.PerUser.allow(owner); !allowed {
		tooMany(w, wait)
		return "", "", false
	}
	if allowed, wait := s.Global.allow("all"); !allowed {
		tooMany(w, wait)
		return "", "", false
	}
	return owner, callerDevice, true
}

func (s *Server) deviceKey(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"key": s.Devices.Proof.PublicBase64()})
}

func readJSON(w http.ResponseWriter, r *http.Request, into any) bool {
	body, err := io.ReadAll(io.LimitReader(r.Body, maxBodyBytes+1))
	if err != nil || len(body) > maxBodyBytes || json.Unmarshal(body, into) != nil {
		fail(w, http.StatusBadRequest, "bad body")
		return false
	}
	return true
}

func (s *Server) enrol(w http.ResponseWriter, r *http.Request) {
	owner, callerDevice, ok := s.deviceCaller(w, r)
	if !ok {
		return
	}
	var req enrolRequest
	if !readJSON(w, r, &req) {
		return
	}
	if !devicePattern.MatchString(req.DeviceID) || (callerDevice != "" && callerDevice != req.DeviceID) {
		fail(w, http.StatusBadRequest, "bad device")
		return
	}
	token, status, reason := s.Devices.Enrol(r.Context(), owner, req)
	if reason != "" {
		fail(w, status, reason)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"token": token, "uid": deviceUID(owner, req.DeviceID)})
}

// Enrol checks that the device proves the key in its cloud record, then adds it to the allow-list and mints its token.
func (d *DeviceAdmin) Enrol(ctx context.Context, owner string, req enrolRequest) (token string, status int, reason string) {
	record, err := d.Store.Device(ctx, owner, req.DeviceID)
	if err != nil {
		return "", http.StatusBadGateway, "record read failed"
	}
	if record == nil {
		return "", http.StatusNotFound, "no record for this device"
	}
	digest, ok := keyDigestHex(record.ClipboardPublicKey)
	if !ok {
		return "", http.StatusForbidden, "record is not bound to a key"
	}
	keyHash := digest[:keyHashHexChars]
	// A key-derived id must match its key. An older random id has nothing to check against: its key is pinned on first enrol.
	if strings.HasPrefix(req.DeviceID, derivedIDPrefix) && (keyHash != record.PublicKeyHash || derivedIDPrefix+digest[:derivedIDHexChars] != req.DeviceID) {
		return "", http.StatusForbidden, "record is not bound to a key"
	}
	if !d.Proof.Verify(record.ClipboardPublicKey, req.DeviceID, req.Ts, req.Nonce, req.Tag) {
		return "", http.StatusForbidden, "proof refused"
	}
	unlock := d.lock(owner)
	defer unlock()
	state, version, err := d.Store.LoadState(ctx, owner)
	if err != nil {
		return "", http.StatusBadGateway, "state read failed"
	}
	if state == nil {
		state = &State{SeedCutoff: d.Now().UTC()}
	}
	state.normalise()
	if _, denied := state.Denied[req.DeviceID]; denied {
		return "", http.StatusForbidden, "device was removed"
	}
	if known, listed := state.Allowed[req.DeviceID]; listed {
		if known != keyHash {
			return "", http.StatusForbidden, "key changed"
		}
	} else if !record.Created.Before(state.SeedCutoff) {
		return "", http.StatusForbidden, "device was not admitted"
	}
	state.Allowed[req.DeviceID] = keyHash
	if err := d.Store.SaveState(ctx, owner, state, version); err != nil {
		return "", saveStatus(err), "state write failed"
	}
	token, err = d.Tokens.Mint(deviceUID(owner, req.DeviceID), map[string]any{"owner": owner, "deviceId": req.DeviceID})
	if err != nil {
		return "", http.StatusInternalServerError, "token failed"
	}
	return token, http.StatusOK, ""
}

func (s *Server) sync(w http.ResponseWriter, r *http.Request) {
	owner, callerDevice, ok := s.deviceCaller(w, r)
	if !ok {
		return
	}
	var req syncRequest
	if !readJSON(w, r, &req) {
		return
	}
	if len(req.Admit) > maxAdmit || len(req.Revoke) > maxRevoke {
		fail(w, http.StatusBadRequest, "bad device")
		return
	}
	for _, e := range req.Admit {
		if !devicePattern.MatchString(e.DeviceID) || !hashPattern.MatchString(e.PublicKeyHash) || e.Version < 0 {
			fail(w, http.StatusBadRequest, "bad device")
			return
		}
	}
	for _, e := range req.Revoke {
		if !devicePattern.MatchString(e.DeviceID) || e.Version < 0 {
			fail(w, http.StatusBadRequest, "bad device")
			return
		}
	}
	if callerDevice == "" {
		fail(w, http.StatusForbidden, "only an enrolled device can change the list")
		return
	}
	changed, status, reason := s.Devices.Sync(r.Context(), owner, callerDevice, req)
	if reason != "" {
		fail(w, status, reason)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{"ok": true, "changed": changed})
}

// denialOf is the version of a device's denial, 0 for one written by an earlier build, -1 when there is none.
func denialOf(state *State, id string) int64 {
	if v, denied := state.Denied[id]; denied {
		return int64(v)
	}
	return -1
}

// Sync brings the allow-list in line with the peers the caller has paired (admit) and removed (revoke), each with the
// cluster version of that fact. A newer version wins, so an old roster cannot undo a removal and a re-pair clears the
// removal it follows. Nothing is written when nothing changes.
func (d *DeviceAdmin) Sync(ctx context.Context, owner, callerDevice string, req syncRequest) (changed bool, status int, reason string) {
	unlock := d.lock(owner)
	defer unlock()
	state, version, err := d.Store.LoadState(ctx, owner)
	if err != nil {
		return false, http.StatusBadGateway, "state read failed"
	}
	if state == nil {
		return false, http.StatusForbidden, "caller is not enrolled"
	}
	state.normalise()
	if _, listed := state.Allowed[callerDevice]; !listed {
		return false, http.StatusForbidden, "caller is not enrolled"
	}
	var disable, enable []string
	for _, e := range req.Revoke {
		denial := denialOf(state, e.DeviceID)
		_, listed := state.Allowed[e.DeviceID]
		if state.Since[e.DeviceID] >= e.Version || (!listed && denial >= e.Version) {
			continue
		}
		delete(state.Allowed, e.DeviceID)
		state.Denied[e.DeviceID] = Version(max(denial, e.Version))
		if listed || denial == -1 {
			disable = append(disable, e.DeviceID)
		}
		changed = true
	}
	for _, e := range req.Admit {
		denial := denialOf(state, e.DeviceID)
		if denial >= e.Version {
			continue
		}
		if state.Allowed[e.DeviceID] == e.PublicKeyHash && denial == -1 {
			continue
		}
		state.Allowed[e.DeviceID] = e.PublicKeyHash
		state.Since[e.DeviceID] = max(state.Since[e.DeviceID], e.Version)
		delete(state.Denied, e.DeviceID)
		if denial != -1 {
			enable = append(enable, e.DeviceID)
		}
		changed = true
	}
	if !changed {
		return false, http.StatusOK, ""
	}
	// The Firebase users are switched before the state is written, so a failure leaves the state alone and a retry redoes it.
	for _, id := range disable {
		if err := d.Identity.SetEnabled(ctx, deviceUID(owner, id), false); err != nil {
			log.Printf("sync: identity update failed: %v", err)
			return false, http.StatusBadGateway, "identity update failed"
		}
	}
	for _, id := range enable {
		if err := d.Identity.SetEnabled(ctx, deviceUID(owner, id), true); err != nil {
			log.Printf("sync: identity update failed: %v", err)
			return false, http.StatusBadGateway, "identity update failed"
		}
	}
	if err := d.Store.SaveState(ctx, owner, state, version); err != nil {
		return false, saveStatus(err), "state write failed"
	}
	return true, http.StatusOK, ""
}

func (s *State) normalise() {
	if s.Allowed == nil {
		s.Allowed = map[string]string{}
	}
	if s.Since == nil {
		s.Since = map[string]int64{}
	}
	if s.Denied == nil {
		s.Denied = map[string]Version{}
	}
}

func saveStatus(err error) int {
	if errors.Is(err, errConflict) {
		return http.StatusConflict
	}
	return http.StatusBadGateway
}
