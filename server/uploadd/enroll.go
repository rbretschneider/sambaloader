// Enrollment endpoints (SERVER_SPEC §7.4/§7.5) — reachable ONLY via the
// admin listener (:8443, LAN). Signing needs ca.key temporarily present
// (mode (a), §3.5); its absence returns 503 ca_key_absent.
package main

import (
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"log"
	"math/big"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	qrcode "github.com/skip2/go-qrcode"
)

const (
	tokenTTL           = 10 * time.Minute // SERVER_SPEC §1.2 rule 7
	deviceCertValidity = 25 * 365 * 24 * time.Hour
)

type caMaterial struct {
	dir     string
	cert    *x509.Certificate
	certPEM string
}

func loadCA(dir string) (*caMaterial, error) {
	certPEM, err := os.ReadFile(filepath.Join(dir, "ca.crt"))
	if err != nil {
		return nil, err
	}
	block, _ := pem.Decode(certPEM)
	if block == nil {
		return nil, fmt.Errorf("ca.crt is not PEM")
	}
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return nil, err
	}
	return &caMaterial{dir: dir, cert: cert, certPEM: string(certPEM)}, nil
}

// key loads ca.key fresh on every call — it is only present during
// enrollment/revocation windows and must never be cached in memory longer
// than one operation.
func (ca *caMaterial) key() (*ecdsa.PrivateKey, error) {
	raw, err := os.ReadFile(filepath.Join(ca.dir, "ca.key"))
	if err != nil {
		return nil, err
	}
	block, _ := pem.Decode(raw)
	if block == nil {
		return nil, fmt.Errorf("ca.key is not PEM")
	}
	return x509.ParseECPrivateKey(block.Bytes)
}

func (ca *caMaterial) fingerprint() string {
	sum := sha256.Sum256(ca.cert.Raw)
	return "SHA256:" + hex.EncodeToString(sum[:])
}

func (s *apiServer) handleEnrollBegin(w http.ResponseWriter, _ *http.Request) {
	// Same minting path as `uploadd -pair`, so the web and terminal
	// flows cannot drift apart.
	token, expires, err := mintPairingToken(s.db)
	if err != nil {
		http.Error(w, "database error", http.StatusInternalServerError)
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"v":              1,
		"url":            strings.TrimRight(s.publicURL, "/"),
		"ca_fingerprint": s.ca.fingerprint(),
		"ca_cert":        s.ca.certPEM,
		"token":          token,
		"expires_at":     expires.Unix(),
	})
}

type completeRequest struct {
	Token string `json:"token"`
	Label string `json:"label"`
	CSR   string `json:"csr"`
}

func (s *apiServer) handleEnrollComplete(w http.ResponseWriter, r *http.Request) {
	var req completeRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid_json"})
		return
	}

	now := time.Now()
	expired, burned, err := burnToken(s.db, req.Token, now.Unix())
	if err != nil {
		http.Error(w, "database error", http.StatusInternalServerError)
		return
	}
	if !burned {
		known, _ := tokenExists(s.db, req.Token)
		code := "token_unknown"
		if known {
			code = "token_used"
		}
		writeJSON(w, http.StatusForbidden, map[string]string{"error": code})
		return
	}
	if expired {
		writeJSON(w, http.StatusForbidden, map[string]string{"error": "token_expired"})
		return
	}

	block, _ := pem.Decode([]byte(req.CSR))
	if block == nil || block.Type != "CERTIFICATE REQUEST" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid_csr"})
		return
	}
	csr, err := x509.ParseCertificateRequest(block.Bytes)
	if err != nil || csr.CheckSignature() != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid_csr"})
		return
	}

	caKey, err := s.ca.key()
	if err != nil {
		// Mode (a): the operator restores ca.key for the enrollment
		// window; the admin page explains this on 503.
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "ca_key_absent"})
		return
	}

	cn, err := s.uniqueCN(sanitizeLabel(req.Label))
	if err != nil {
		http.Error(w, "database error", http.StatusInternalServerError)
		return
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 120))
	if err != nil {
		http.Error(w, "entropy failure", http.StatusInternalServerError)
		return
	}
	template := &x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: cn},
		NotBefore:    now.Add(-time.Hour),
		NotAfter:     now.Add(deviceCertValidity),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, template, s.ca.cert, csr.PublicKey, caKey)
	if err != nil {
		http.Error(w, "signing failed", http.StatusInternalServerError)
		return
	}
	serialHex := "0x" + serial.Text(16)
	if _, err := s.db.Exec(
		"INSERT INTO devices (serial, cn, label, enrolled_at) VALUES (?, ?, ?, ?)",
		serialHex, cn, req.Label, now.Unix(),
	); err != nil {
		http.Error(w, "database error", http.StatusInternalServerError)
		return
	}

	log.Printf("enrolled %q as %q (serial %s)", req.Label, cn, serialHex)
	writeJSON(w, http.StatusCreated, map[string]any{
		"certificate":    string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})),
		"ca_certificate": s.ca.certPEM,
		"serial":         serialHex,
		"expires_at":     template.NotAfter.Unix(),
	})
}

func (s *apiServer) handleQR(w http.ResponseWriter, r *http.Request) {
	data, err := base64.RawURLEncoding.DecodeString(r.URL.Query().Get("d"))
	if err != nil || len(data) == 0 {
		http.Error(w, "bad payload", http.StatusBadRequest)
		return
	}
	png, err := qrcode.Encode(string(data), qrcode.Medium, 480)
	if err != nil {
		http.Error(w, "qr encode failed", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "image/png")
	_, _ = w.Write(png)
}

func (s *apiServer) handleDeviceList(w http.ResponseWriter, _ *http.Request) {
	rows, err := s.db.Query(
		"SELECT serial, cn, label, enrolled_at, last_seen_at, revoked_at, bytes_total, assets_total FROM devices ORDER BY enrolled_at",
	)
	if err != nil {
		http.Error(w, "database error", http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	type deviceRow struct {
		Serial      string `json:"serial"`
		CN          string `json:"cn"`
		Label       string `json:"label"`
		EnrolledAt  int64  `json:"enrolled_at"`
		LastSeenAt  *int64 `json:"last_seen_at"`
		RevokedAt   *int64 `json:"revoked_at"`
		BytesTotal  int64  `json:"bytes_total"`
		AssetsTotal int64  `json:"assets_total"`
	}
	devices := []deviceRow{}
	for rows.Next() {
		var d deviceRow
		if err := rows.Scan(&d.Serial, &d.CN, &d.Label, &d.EnrolledAt, &d.LastSeenAt, &d.RevokedAt, &d.BytesTotal, &d.AssetsTotal); err != nil {
			http.Error(w, "database error", http.StatusInternalServerError)
			return
		}
		devices = append(devices, d)
	}
	writeJSON(w, http.StatusOK, map[string]any{"devices": devices})
}

/**
 * Revokes a device from the admin page. Requires ca.key to be present
 * (same window as enrollment); nginx picks up the regenerated CRL via
 * the reload watcher, so the device fails its next handshake.
 */
func (s *apiServer) handleRevoke(w http.ResponseWriter, r *http.Request) {
	serial := r.PathValue("serial")
	if serial == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "missing_serial"})
		return
	}
	err := revokeWithCA(s.db, s.ca, s.ca.dir, serial)
	switch {
	case errors.Is(err, errCaKeyAbsent):
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "ca_key_absent"})
	case errors.Is(err, errUnknownDevice):
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "unknown_device"})
	case err != nil:
		log.Printf("revoke %s failed: %v", serial, err)
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "revoke_failed"})
	default:
		writeJSON(w, http.StatusOK, map[string]string{"status": "revoked", "serial": serial})
	}
}

// uniqueCN appends a numeric suffix on collision (SERVER_SPEC §7.5).
func (s *apiServer) uniqueCN(base string) (string, error) {
	cn := base
	for suffix := 2; ; suffix++ {
		var one int
		err := s.db.QueryRow("SELECT 1 FROM devices WHERE cn = ?", cn).Scan(&one)
		if err == sql.ErrNoRows {
			return cn, nil
		}
		if err != nil {
			return "", err
		}
		cn = fmt.Sprintf("%s-%d", base, suffix)
	}
}

var labelUnsafe = regexp.MustCompile(`[^a-z0-9-]+`)

func sanitizeLabel(label string) string {
	clean := labelUnsafe.ReplaceAllString(strings.ToLower(strings.TrimSpace(label)), "-")
	clean = strings.Trim(clean, "-")
	if clean == "" {
		clean = "device"
	}
	if len(clean) > 64 {
		clean = clean[:64]
	}
	return clean
}
