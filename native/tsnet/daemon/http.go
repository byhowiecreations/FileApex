package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"mime"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

func (n *Node) serve() error {
	if err := n.serveHTTP(); err != nil {
		return err
	}
	n.serveTLS()
	return nil
}

func (n *Node) serveHTTP() error {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.httpServer != nil {
		return nil
	}
	ln, err := net.Listen("tcp", fmt.Sprintf(":%d", n.port))
	if err != nil {
		return fmt.Errorf("listen on TCP %d: %w", n.port, err)
	}
	tcp, ok := ln.Addr().(*net.TCPAddr)
	if !ok {
		ln.Close()
		return fmt.Errorf("unexpected listen address %s", ln.Addr())
	}
	n.port = tcp.Port
	srv := &http.Server{
		Handler:           n.guard(n.routes(), false),
		ReadHeaderTimeout: 30 * time.Second,
	}
	n.httpServer = srv
	go func() {
		err := srv.Serve(ln)
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Printf("HTTP server stopped: %v", err)
		}
	}()
	log.Printf("File API listening on %s", ln.Addr())
	return nil
}

func (n *Node) shutdown(timeout time.Duration) {
	n.mu.Lock()
	srv := n.httpServer
	n.mu.Unlock()
	if srv == nil {
		return
	}
	if timeout <= 0 {
		timeout = 2 * time.Second
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	_ = srv.Shutdown(ctx)
	n.shutdownTLS(timeout)
}

func (n *Node) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/api/v1/health", n.handleHealth)
	mux.HandleFunc("/api/v1/identity", n.handleIdentity)
	mux.HandleFunc("/api/v1/heartbeat", n.handleIdentity)
	mux.HandleFunc("/api/v1/devices", n.handleDevices)
	mux.HandleFunc("/api/v1/devices/merge", n.handleMerge)
	mux.HandleFunc("/api/v1/cluster/remove", n.handleRemove)
	mux.HandleFunc("/cluster/remove", n.handleRemove)
	mux.HandleFunc("/api/v1/identity/rename", n.handleRename)
	mux.HandleFunc("/api/v1/files/capabilities", n.handleCapabilities)
	mux.HandleFunc("/api/v1/files/list", n.handleList)
	mux.HandleFunc("/api/v1/files/stream", n.handleStream)
	mux.HandleFunc("/api/v1/files/resume", n.handleResume)
	mux.HandleFunc("/api/v1/files/upload", n.handleUpload)
	mux.HandleFunc("/api/v1/files/mkdir", n.handleMkdir)
	mux.HandleFunc("/api/v1/files/delete", n.handleDelete)
	mux.HandleFunc("/api/v1/diagnostics", n.handleDiagnostics)
	mux.HandleFunc("/api/v1/tls/pin", n.handleTLSPin)
	mux.HandleFunc("/api/v1/clipboard/status", n.handleClipboardStatus)
	mux.HandleFunc("/api/v1/clipboard/send", n.handleClipboardSend)
	return mux
}

func (n *Node) handleHealth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !n.pass(w, r, "open") {
		return
	}
	writeText(w, http.StatusOK, "ok")
}

func (n *Node) handleIdentity(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !n.pass(w, r, "open") {
		return
	}
	writeJSON(w, http.StatusOK, n.selfState())
}

func (n *Node) handleDevices(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !n.pass(w, r, "open") {
		return
	}
	writeJSON(w, http.StatusOK, n.publishedRoster())
}

func (n *Node) handleCapabilities(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !n.pass(w, r, "peer") {
		return
	}
	// Single-stream uploads cover backup copies, including large files.
	writeJSON(w, http.StatusOK, map[string]bool{
		"rangedStream":    false,
		"segmentedUpload": false,
		"backupSync":      true,
	})
}

func (n *Node) handleMerge(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	id, mv := requestSender(r)
	if !n.allowMerge(id, mv) {
		log.Printf("Rejected cluster sync from unpaired device %q", id)
		http.Error(w, "peer_not_paired", http.StatusForbidden)
		return
	}
	n.noteContact(id, r.RemoteAddr)
	body, err := io.ReadAll(io.LimitReader(r.Body, 8<<20))
	if err != nil || len(bytesTrim(body)) == 0 {
		http.Error(w, "Empty cluster sync payload", http.StatusBadRequest)
		return
	}
	var req clusterSync
	if err := json.Unmarshal(body, &req); err != nil {
		http.Error(w, "Invalid sync payload", http.StatusBadRequest)
		return
	}
	if req.EventKind == "" {
		req.EventKind = "SELF_METADATA"
	}
	if n.applySync(req) {
		log.Printf("Removed from the cluster. Listening for a new pairing broadcast.")
	} else {
		log.Printf("Cluster sync %s from %s (%d peers now)", req.EventKind, id, len(n.roster()))
	}
	writeJSON(w, http.StatusOK, n.publishedRoster())
}

func (n *Node) handleRemove(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil || len(bytesTrim(body)) == 0 {
		http.Error(w, "Empty removal payload", http.StatusBadRequest)
		return
	}
	var record removedRecord
	if err := json.Unmarshal(body, &record); err != nil {
		http.Error(w, "Invalid removal payload", http.StatusBadRequest)
		return
	}
	self := strings.TrimSpace(record.DeviceID) == n.deviceID()
	left := n.applySync(clusterSync{EventKind: "REMOVAL", RemovedDevices: []removedRecord{record}})
	if self {
		if left {
			log.Printf("This node was removed from the cluster.")
			_, _ = w.Write([]byte("revoked_acknowledged"))
		} else {
			_, _ = w.Write([]byte("revocation_ignored"))
		}
		return
	}
	w.WriteHeader(http.StatusOK)
}

func (n *Node) handleRename(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !n.pass(w, r, "peer") {
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 64<<10))
	if err != nil {
		http.Error(w, "rename_failed", http.StatusBadRequest)
		return
	}
	var req struct {
		DeviceName string `json:"deviceName"`
	}
	if err := json.Unmarshal(body, &req); err != nil {
		http.Error(w, "rename_failed", http.StatusBadRequest)
		return
	}
	name := strings.TrimSpace(req.DeviceName)
	if name == "" {
		http.Error(w, "empty_name", http.StatusBadRequest)
		return
	}
	n.renameSelf(name)
	log.Printf("Device name is now %s", name)
	w.WriteHeader(http.StatusOK)
	go n.announceSelf(context.Background(), "SELF_METADATA")
}

func (n *Node) handleList(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !n.pass(w, r, "peer") {
		return
	}
	raw := strings.TrimSpace(r.URL.Query().Get("path"))
	resolved, err := n.resolvePath(raw, true)
	if err != nil {
		http.Error(w, "Path outside shared root", http.StatusForbidden)
		return
	}
	entries, err := os.ReadDir(resolved)
	if err != nil {
		if os.IsNotExist(err) {
			http.NotFound(w, r)
		} else {
			http.Error(w, "list_failed", http.StatusBadRequest)
		}
		return
	}
	items := make([]fileItem, 0, len(entries))
	for _, entry := range entries {
		if isScratch(entry.Name()) {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			continue
		}
		abs := filepath.Join(resolved, entry.Name())
		item := fileItem{
			ID:           abs,
			Name:         entry.Name(),
			AbsolutePath: abs,
			LastModified: info.ModTime().UnixMilli(),
			IsDirectory:  entry.IsDir(),
			MimeType:     mimeFor(entry.Name(), entry.IsDir()),
		}
		if !entry.IsDir() {
			item.SizeBytes = info.Size()
		}
		items = append(items, item)
	}
	writeJSON(w, http.StatusOK, items)
}

func (n *Node) handleStream(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !n.pass(w, r, "peer") {
		return
	}
	raw := strings.TrimSpace(r.URL.Query().Get("path"))
	if raw == "" {
		http.Error(w, "Missing path", http.StatusBadRequest)
		return
	}
	resolved, err := n.resolvePath(raw, false)
	if err != nil {
		http.Error(w, "Path outside shared root", http.StatusForbidden)
		return
	}
	file, err := os.Open(resolved)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil || info.IsDir() {
		http.NotFound(w, r)
		return
	}
	size := info.Size()
	offset := queryInt64(r, "offset")
	if offset < 0 {
		offset = 0
	}
	if offset > size {
		w.Header().Set("Content-Range", fmt.Sprintf("bytes */%d", size))
		w.WriteHeader(http.StatusRequestedRangeNotSatisfiable)
		return
	}
	length := size - offset
	if want := queryInt64(r, "length"); want > 0 && want < length {
		length = want
	}
	partial := offset > 0 || length < size
	w.Header().Set("Accept-Ranges", "bytes")
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.FormatInt(length, 10))
	status := http.StatusOK
	if partial && length > 0 {
		status = http.StatusPartialContent
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", offset, offset+length-1, size))
	}
	w.WriteHeader(status)
	if length == 0 {
		return
	}
	if _, err := file.Seek(offset, io.SeekStart); err != nil {
		return
	}
	_, _ = io.CopyN(w, file, length)
}

func (n *Node) handleResume(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !n.pass(w, r, "peer") {
		return
	}
	raw := strings.TrimSpace(r.URL.Query().Get("targetPath"))
	if raw == "" {
		http.Error(w, "Missing targetPath", http.StatusBadRequest)
		return
	}
	resolved, err := n.resolvePath(raw, false)
	if err != nil {
		http.Error(w, "Path outside shared root", http.StatusForbidden)
		return
	}
	expected := queryInt64(r, "expectedSize")
	tx := r.URL.Query().Get("txId")
	sender, _ := requestSender(r)
	if entry, ok := n.findCompleted(tx, sender, expected); ok {
		writeJSON(w, http.StatusOK, map[string]any{"offset": entry.Size, "complete": true})
		return
	}
	finalPath := uniqueName(resolved)
	part := finalPath + partSuffix
	onDisk := fileSize(part)
	if expected > 0 && onDisk > expected {
		writeJSON(w, http.StatusOK, map[string]any{"offset": int64(0), "complete": false})
		return
	}
	complete := expected > 0 && onDisk == expected
	if complete {
		_ = os.Rename(part, finalPath)
	}
	writeJSON(w, http.StatusOK, map[string]any{"offset": onDisk, "complete": complete})
}

func (n *Node) handleUpload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	sender, _ := requestSender(r)
	if !n.pass(w, r, "peer") {
		return
	}
	raw := strings.TrimSpace(r.URL.Query().Get("targetPath"))
	if raw == "" {
		http.Error(w, "Missing targetPath", http.StatusBadRequest)
		return
	}
	resolved, err := n.resolvePath(raw, false)
	if err != nil {
		http.Error(w, "Path outside shared root", http.StatusForbidden)
		return
	}
	tx := strings.TrimSpace(r.URL.Query().Get("txId"))
	totalSize := queryInt64(r, "totalSize")
	if entry, ok := n.findCompleted(tx, sender, totalSize); ok {
		_, _ = io.Copy(io.Discard, r.Body)
		log.Printf("Upload already stored tx=%s path=%s", tx, entry.FinalPath)
		writeText(w, http.StatusCreated, "ok")
		return
	}
	offset := queryInt64(r, "offset")
	if offset < 0 {
		offset = 0
	}
	// Folder backup (backup=1) replaces an older copy in place; every other upload is numbered.
	backup := r.URL.Query().Get("backup") == "1"
	n.uploadMu.Lock()
	finalPath := resolved
	if !backup {
		finalPath = uniqueName(resolved)
	}
	part := finalPath + partSuffix
	if err := os.MkdirAll(filepath.Dir(finalPath), 0o755); err != nil {
		n.uploadMu.Unlock()
		http.Error(w, "upload_failed", http.StatusInternalServerError)
		return
	}
	partFile, err := os.OpenFile(part, os.O_CREATE|os.O_RDWR, 0o644)
	if err != nil {
		n.uploadMu.Unlock()
		http.Error(w, "upload_failed", http.StatusInternalServerError)
		return
	}
	info, err := partFile.Stat()
	if err != nil {
		partFile.Close()
		n.uploadMu.Unlock()
		http.Error(w, "upload_failed", http.StatusInternalServerError)
		return
	}
	if offset > info.Size() {
		partFile.Close()
		n.uploadMu.Unlock()
		http.Error(w, "resume_offset_invalid", http.StatusBadRequest)
		return
	}
	if err := partFile.Truncate(offset); err != nil {
		partFile.Close()
		n.uploadMu.Unlock()
		http.Error(w, "upload_failed", http.StatusInternalServerError)
		return
	}
	if _, err := partFile.Seek(offset, io.SeekStart); err != nil {
		partFile.Close()
		n.uploadMu.Unlock()
		http.Error(w, "upload_failed", http.StatusInternalServerError)
		return
	}
	n.uploadMu.Unlock()

	received, copyErr := io.Copy(partFile, r.Body)
	_ = partFile.Sync()
	_ = partFile.Close()
	total := offset + received
	session := r.ContentLength
	complete := false
	switch {
	case copyErr != nil:
		complete = false
	case received <= 0 && offset == 0:
		complete = false
	case totalSize > 0:
		complete = total == totalSize
	case session >= 0:
		complete = received == session
	default:
		complete = received > 0
	}
	if !complete {
		if total <= 0 {
			_ = os.Remove(part)
		}
		reason := "upload_incomplete"
		if total <= 0 {
			reason = "upload_empty"
		}
		log.Printf("Upload paused path=%s offset=%d received=%d reason=%s", part, offset, received, reason)
		http.Error(w, reason, http.StatusBadRequest)
		return
	}
	n.uploadMu.Lock()
	dest := finalPath
	if _, err := os.Lstat(dest); err == nil && !backup {
		dest = uniqueName(dest)
	}
	renameErr := os.Rename(part, dest)
	n.uploadMu.Unlock()
	if renameErr != nil {
		log.Printf("Could not finish upload %s: %v", dest, renameErr)
		http.Error(w, "upload_failed", http.StatusInternalServerError)
		return
	}
	if tx != "" {
		n.rememberCompleted(diskTx{
			TransactionID: tx,
			SenderID:      sender,
			FinalPath:     dest,
			Size:          total,
		})
	}
	log.Printf("Received %s (%d bytes) from %s", dest, total, n.peerLabel(sender))
	writeText(w, http.StatusCreated, "ok")
}

func (n *Node) handleMkdir(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !n.pass(w, r, "peer") {
		return
	}
	raw := strings.TrimSpace(r.URL.Query().Get("targetPath"))
	if raw == "" {
		http.Error(w, "Missing targetPath", http.StatusBadRequest)
		return
	}
	resolved, err := n.resolvePath(raw, true)
	if err != nil {
		http.Error(w, "Path outside shared root", http.StatusForbidden)
		return
	}
	if err := os.MkdirAll(resolved, 0o755); err != nil {
		http.Error(w, "mkdir_failed", http.StatusInternalServerError)
		return
	}
	writeText(w, http.StatusCreated, "ok")
}

// handleDelete removes a file or folder inside the inbox on behalf of a paired device. There is no
// trash here, so it is permanent; the sender confirms first. Set FILEAPEX_ALLOW_REMOTE_DELETE=0 to refuse.
func (n *Node) handleDelete(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !n.pass(w, r, "peer") {
		return
	}
	switch strings.ToLower(strings.TrimSpace(os.Getenv("FILEAPEX_ALLOW_REMOTE_DELETE"))) {
	case "0", "false", "no", "off":
		http.Error(w, "remote_delete_disabled", http.StatusForbidden)
		return
	}
	raw := strings.TrimSpace(r.URL.Query().Get("targetPath"))
	if raw == "" {
		http.Error(w, "Missing targetPath", http.StatusBadRequest)
		return
	}
	// allowRoot=false: the inbox itself can never be deleted.
	resolved, err := n.resolvePath(raw, false)
	if err != nil {
		http.Error(w, "Path outside shared root", http.StatusForbidden)
		return
	}
	if err := os.RemoveAll(resolved); err != nil {
		log.Printf("Remote delete failed %s: %v", resolved, err)
		http.Error(w, "delete_failed", http.StatusInternalServerError)
		return
	}
	sender, _ := requestSender(r)
	log.Printf("Deleted %s at the request of %s", resolved, n.peerLabel(sender))
	writeText(w, http.StatusOK, "ok")
}

func (n *Node) pass(w http.ResponseWriter, r *http.Request, kind string) bool {
	id, mv := requestSender(r)
	if id != "" && n.isRevoked(id, mv) {
		w.Header().Set("X-FileApex-Status", "revoked")
		http.Error(w, "revoked", http.StatusUnauthorized)
		return false
	}
	if kind == "peer" && !n.allowUpload(id, mv) {
		log.Printf("Rejected %s %s from unpaired device %q", r.Method, r.URL.Path, id)
		http.Error(w, "peer_not_paired", http.StatusForbidden)
		return false
	}
	if id != "" {
		n.noteContact(id, r.RemoteAddr)
	}
	return true
}

func (n *Node) peerLabel(id string) string {
	for _, peer := range n.roster() {
		if peer.DeviceID == id {
			if peer.DeviceName != "" {
				return peer.DeviceName
			}
			break
		}
	}
	if id == "" {
		return "unknown device"
	}
	return id
}

func (n *Node) resolvePath(requested string, allowRoot bool) (string, error) {
	root, err := filepath.Abs(n.inboxDir())
	if err != nil {
		return "", err
	}
	root, err = resolveExisting(root)
	if err != nil {
		return "", err
	}
	requested = strings.TrimSpace(requested)
	var candidate string
	switch {
	case requested == "" || requested == "/" || requested == `\`:
		candidate = root
	case filepath.IsAbs(requested):
		candidate = filepath.Clean(requested)
	default:
		candidate = filepath.Clean(filepath.Join(root, requested))
	}
	candidate = stripSenderReceiveFolder(root, candidate)
	resolved, err := resolveExisting(candidate)
	if err != nil {
		return "", err
	}
	if !pathInside(root, resolved) {
		return "", errOutsideRoot
	}
	if !allowRoot && resolved == root {
		return "", errOutsideRoot
	}
	return resolved, nil
}

// resolveExisting evaluates symlinks on the nearest existing ancestor so a
// not-yet-created file is still checked against the real inbox path.
func resolveExisting(path string) (string, error) {
	path = filepath.Clean(path)
	if resolved, err := filepath.EvalSymlinks(path); err == nil {
		return filepath.Clean(resolved), nil
	}
	var suffix []string
	cur := path
	for {
		parent := filepath.Dir(cur)
		if parent == cur {
			return "", errOutsideRoot
		}
		suffix = append([]string{filepath.Base(cur)}, suffix...)
		if resolved, err := filepath.EvalSymlinks(parent); err == nil {
			parts := append([]string{resolved}, suffix...)
			return filepath.Clean(filepath.Join(parts...)), nil
		}
		cur = parent
	}
}

// Senders build a Linux peer path as <root>/Downloads/FileApex/<file>.
// This node's root is already the receive folder, so that prefix is removed
// and only a transferred directory is kept underneath it.
func stripSenderReceiveFolder(root, full string) string {
	rel, err := filepath.Rel(root, full)
	if err != nil || rel == "." || strings.HasPrefix(rel, "..") {
		return full
	}
	parts := strings.Split(filepath.ToSlash(rel), "/")
	if len(parts) < 2 {
		return full
	}
	folder := strings.ToLower(parts[0])
	if (folder != "downloads" && folder != "download") || !strings.EqualFold(parts[1], "FileApex") {
		return full
	}
	rest := parts[2:]
	if len(rest) == 0 {
		return root
	}
	return filepath.Join(append([]string{root}, rest...)...)
}

var errOutsideRoot = errors.New("path outside shared root")

func pathInside(root, candidate string) bool {
	rel, err := filepath.Rel(root, candidate)
	if err != nil {
		return false
	}
	return rel == "." || (rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)))
}

type fileItem struct {
	ID           string `json:"id"`
	Name         string `json:"name"`
	AbsolutePath string `json:"absolutePath"`
	SizeBytes    int64  `json:"sizeBytes"`
	LastModified int64  `json:"lastModified"`
	IsDirectory  bool   `json:"isDirectory"`
	MimeType     string `json:"mimeType"`
}

func requestSender(r *http.Request) (string, int64) {
	q := r.URL.Query()
	id := strings.TrimSpace(q.Get("from"))
	if id == "" {
		id = strings.TrimSpace(r.Header.Get("X-FileApex-Device-Id"))
	}
	if id == "" {
		id = strings.TrimSpace(q.Get("senderId"))
	}
	mv, _ := strconv.ParseInt(q.Get("mv"), 10, 64)
	if mv == 0 {
		mv, _ = strconv.ParseInt(r.Header.Get("X-FileApex-Membership-Version"), 10, 64)
	}
	return id, mv
}

func queryInt64(r *http.Request, key string) int64 {
	v, _ := strconv.ParseInt(r.URL.Query().Get(key), 10, 64)
	return v
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	body, err := json.Marshal(value)
	if err != nil {
		http.Error(w, "encode failed", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Content-Length", strconv.Itoa(len(body)))
	w.WriteHeader(status)
	_, _ = w.Write(body)
}

func writeText(w http.ResponseWriter, status int, text string) {
	body := []byte(text)
	w.Header().Set("Content-Type", "text/plain")
	w.Header().Set("Content-Length", strconv.Itoa(len(body)))
	w.WriteHeader(status)
	_, _ = w.Write(body)
}

func bytesTrim(body []byte) []byte {
	return []byte(strings.TrimSpace(string(body)))
}

func mimeFor(name string, dir bool) string {
	if dir {
		return "inode/directory"
	}
	if t := mime.TypeByExtension(strings.ToLower(filepath.Ext(name))); t != "" {
		return t
	}
	return "application/octet-stream"
}

func isScratch(name string) bool {
	return strings.HasSuffix(name, partSuffix) || strings.Contains(name, ".fileapex-segpart")
}

func uniqueName(preferred string) string {
	if _, err := os.Lstat(preferred); err != nil {
		return preferred
	}
	dir := filepath.Dir(preferred)
	name := filepath.Base(preferred)
	for i := 1; i < 10000; i++ {
		candidate := filepath.Join(dir, numberedName(name, i))
		if _, err := os.Lstat(candidate); err != nil {
			return candidate
		}
	}
	return preferred
}

func numberedName(fileName string, index int) string {
	dot := strings.LastIndex(fileName, ".")
	if dot <= 0 {
		return fmt.Sprintf("%s (%d)", fileName, index)
	}
	return fmt.Sprintf("%s (%d)%s", fileName[:dot], index, fileName[dot:])
}

func fileSize(path string) int64 {
	info, err := os.Stat(path)
	if err != nil || info.IsDir() {
		return 0
	}
	if info.Size() < 0 {
		return 0
	}
	return info.Size()
}
