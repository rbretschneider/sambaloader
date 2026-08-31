// mTLS-guarded API handlers (SERVER_SPEC §7.1–§7.3).
package main

import (
	"database/sql"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const (
	maxCheckBatch = 500
	version       = "1.0.0"
)

type apiServer struct {
	db        *sql.DB
	store     Store
	ca        *caMaterial
	publicURL string
}

func (s *apiServer) handleHealth(w http.ResponseWriter, r *http.Request) {
	deviceCN := r.Header.Get("X-Device-CN")
	touchDevice(s.db, deviceCN, time.Now().Unix())
	writeJSON(w, http.StatusOK, map[string]any{
		"version":     version,
		"device":      deviceCN,
		"server_time": time.Now().Unix(),
	})
}

func (s *apiServer) handleCheck(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Hashes []string `json:"hashes"`
	}
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
		if !isValidSha256Hex(hash) {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid_hash"})
			return
		}
		var one int
		err := s.db.QueryRow("SELECT 1 FROM assets WHERE sha256 = ?", hash).Scan(&one)
		switch {
		case err == sql.ErrNoRows:
			want = append(want, hash)
		case err != nil:
			http.Error(w, "database error", http.StatusInternalServerError)
			return
		default:
			have = append(have, hash)
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"have": have, "want": want})
}

func (s *apiServer) handleUpload(w http.ResponseWriter, r *http.Request) {
	deviceCN := r.Header.Get("X-Device-CN")
	declared := strings.ToLower(r.Header.Get("X-Asset-Sha256"))
	capturedAt := parseUnixSeconds(r.Header.Get("X-Asset-Captured-At"))
	// Percent-encoded UTF-8 per SERVER_SPEC v1.1 (headers are ASCII-only).
	filename, nameErr := url.QueryUnescape(r.Header.Get("X-Asset-Filename"))

	if !isValidSha256Hex(declared) || capturedAt == 0 || filename == "" || nameErr != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "missing_or_malformed_headers"})
		return
	}

	// Dedupe before touching disk: 200 = already present, body discarded.
	var one int
	err := s.db.QueryRow("SELECT 1 FROM assets WHERE sha256 = ?", declared).Scan(&one)
	if err == nil {
		writeJSON(w, http.StatusOK, map[string]string{"status": "already_present"})
		return
	}
	if err != sql.ErrNoRows {
		http.Error(w, "database error", http.StatusInternalServerError)
		return
	}

	meta := AssetMeta{CapturedAt: time.Unix(capturedAt, 0), Filename: filename}
	relative, err := s.store.Put(declared, meta, r.Body)
	switch {
	case errors.Is(err, errHashMismatch):
		writeJSON(w, http.StatusConflict, map[string]string{"error": "hash_mismatch"})
		return
	case isNoSpace(err):
		writeJSON(w, http.StatusInsufficientStorage, map[string]string{"error": "out_of_space"})
		return
	case err != nil:
		log.Printf("store failed for %s: %v", declared[:12], err)
		http.Error(w, "storage error", http.StatusInternalServerError)
		return
	}

	if err := recordUpload(s.db, deviceCN, declared, relative, r.ContentLength, time.Now().Unix()); err != nil {
		// A concurrent identical upload can win the insert race; that is
		// still success — the content is stored exactly once.
		writeJSON(w, http.StatusOK, map[string]string{"status": "already_present"})
		return
	}

	log.Printf("stored %s (%s) from %q", relative, declared[:12], deviceCN)
	writeJSON(w, http.StatusCreated, map[string]string{"status": "stored", "path": relative})
}

func isValidSha256Hex(value string) bool {
	if len(value) != 64 {
		return false
	}
	for _, c := range value {
		if (c < '0' || c > '9') && (c < 'a' || c > 'f') {
			return false
		}
	}
	return true
}

func parseUnixSeconds(value string) int64 {
	var seconds int64
	for _, c := range value {
		if c < '0' || c > '9' {
			return 0
		}
		seconds = seconds*10 + int64(c-'0')
	}
	return seconds
}

func isNoSpace(err error) bool {
	return err != nil && strings.Contains(err.Error(), "no space left")
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}
