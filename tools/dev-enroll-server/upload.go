// Upload endpoints (SERVER_SPEC §7.2/§7.3) for the dev server: dedupe by
// content hash, stream-verify, write into a local library folder standing in
// for the NAS. In-memory asset index — restarting forgets what it holds.
package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const maxCheckBatch = 500

type library struct {
	root   string
	mu     sync.Mutex
	assets map[string]string // sha256 -> relative path
}

func newLibrary(root string) *library {
	return &library{root: root, assets: map[string]string{}}
}

func (l *library) has(hash string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	_, ok := l.assets[hash]
	return ok
}

type checkRequest struct {
	Hashes []string `json:"hashes"`
}

func (l *library) handleCheck(w http.ResponseWriter, r *http.Request) {
	var req checkRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid_json"})
		return
	}
	if len(req.Hashes) > maxCheckBatch {
		writeJSON(w, http.StatusRequestEntityTooLarge, map[string]string{"error": "batch_too_large"})
		return
	}
	have := []string{}
	want := []string{}
	for _, hash := range req.Hashes {
		if l.has(hash) {
			have = append(have, hash)
		} else {
			want = append(want, hash)
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"have": have, "want": want})
}

func (l *library) handleUpload(w http.ResponseWriter, r *http.Request) {
	declared := strings.ToLower(r.Header.Get("X-Asset-Sha256"))
	capturedAt := r.Header.Get("X-Asset-Captured-At")
	filename, err := url.QueryUnescape(r.Header.Get("X-Asset-Filename"))
	if declared == "" || capturedAt == "" || filename == "" || err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "missing_headers"})
		return
	}
	if l.has(declared) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "already_present"})
		return
	}

	incoming := filepath.Join(l.root, ".incoming")
	if err := os.MkdirAll(incoming, 0o755); err != nil {
		http.Error(w, "library unavailable", http.StatusInternalServerError)
		return
	}
	temp, err := os.CreateTemp(incoming, "upload-*.part")
	if err != nil {
		http.Error(w, "library unavailable", http.StatusInternalServerError)
		return
	}
	defer os.Remove(temp.Name())

	hasher := sha256.New()
	if _, err := io.Copy(io.MultiWriter(temp, hasher), r.Body); err != nil {
		temp.Close()
		return // client went away; temp removed by defer
	}
	if err := temp.Sync(); err != nil {
		temp.Close()
		http.Error(w, "fsync failed", http.StatusInternalServerError)
		return
	}
	temp.Close()

	actual := hex.EncodeToString(hasher.Sum(nil))
	if actual != declared {
		writeJSON(w, http.StatusConflict, map[string]string{"error": "hash_mismatch"})
		return
	}

	relative := datedPath(capturedAt, filename, actual)
	final := filepath.Join(l.root, relative)
	if err := os.MkdirAll(filepath.Dir(final), 0o755); err != nil {
		http.Error(w, "library unavailable", http.StatusInternalServerError)
		return
	}
	if err := os.Rename(temp.Name(), final); err != nil {
		http.Error(w, "rename failed", http.StatusInternalServerError)
		return
	}

	l.mu.Lock()
	l.assets[actual] = relative
	l.mu.Unlock()

	log.Printf("stored %s (%s) from %q", relative, actual[:12], r.Header.Get("X-Device-CN"))
	writeJSON(w, http.StatusCreated, map[string]string{"status": "stored", "path": relative})
}

// photos/YYYY/MM/YYYY-MM-DD_HHMMSS_<hash6><ext> per SERVER_SPEC §6.4.
func datedPath(capturedAtUnix, filename, hash string) string {
	var seconds int64
	fmt.Sscanf(capturedAtUnix, "%d", &seconds)
	when := time.Unix(seconds, 0).UTC()
	if seconds <= 0 {
		when = time.Now().UTC()
	}
	ext := filepath.Ext(filename)
	if ext == "" {
		ext = ".bin"
	}
	return filepath.Join(
		"photos",
		when.Format("2006"),
		when.Format("01"),
		fmt.Sprintf("%s_%s%s", when.Format("2006-01-02_150405"), hash[:6], ext),
	)
}
