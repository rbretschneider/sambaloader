// Dev enrollment server for Sambaloader (M2). Implements just enough of
// docs/SERVER_SPEC.md to pair a real device and answer an authenticated
// health check:
//
//   - admin listener (plain TLS, no client cert):
//     GET  /              admin page: button -> QR + fingerprint
//     POST /enroll/begin  §7.4 pairing token + QR payload
//     GET  /qr?d=...      payload rendered as a QR PNG
//     POST /enroll/complete §7.5 CSR -> signed device certificate
//   - api listener (mTLS required): GET /api/v1/health §7.1
//
// Superseded by the full devserver/ harness in M5. Dev use only.
package main

import (
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"flag"
	"fmt"
	"log"
	"math/big"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"

	qrcode "github.com/skip2/go-qrcode"
)

const (
	tokenTTL           = 10 * time.Minute
	deviceCertValidity = 25 * 365 * 24 * time.Hour
	version            = "0.1.0-dev"
)

type server struct {
	pkiDir     string
	publicURL  string
	caCert     *x509.Certificate
	caCertPEM  string
	caKey      *ecdsa.PrivateKey
	mu         sync.Mutex
	tokens     map[string]time.Time // token -> expiry; deleted when used
}

func main() {
	pkiDir := flag.String("pki", "../dev-pki/out", "directory holding ca/server key material")
	publicURL := flag.String("url", "https://localhost:9443", "API base URL placed in the QR payload")
	apiAddr := flag.String("api", ":9443", "mTLS API listen address")
	adminAddr := flag.String("admin", ":8443", "enrollment/admin listen address (LAN only)")
	libraryDir := flag.String("library", "./library", "upload destination standing in for the NAS")
	flag.Parse()

	s, err := load(*pkiDir, *publicURL)
	if err != nil {
		log.Fatalf("failed to load PKI from %s: %v (run tools/dev-pki/generate.sh first)", *pkiDir, err)
	}

	go s.serveAPI(*apiAddr, newLibrary(*libraryDir))
	log.Printf("admin/enroll on https://localhost%s  |  mTLS API on %s  |  QR url %s", *adminAddr, *apiAddr, *publicURL)
	log.Fatal(s.serveAdmin(*adminAddr))
}

func load(pkiDir, publicURL string) (*server, error) {
	caCertPEM, err := os.ReadFile(filepath.Join(pkiDir, "ca.crt"))
	if err != nil {
		return nil, err
	}
	caCert, err := parseCertPEM(caCertPEM)
	if err != nil {
		return nil, err
	}
	caKeyPEM, err := os.ReadFile(filepath.Join(pkiDir, "ca.key"))
	if err != nil {
		return nil, err
	}
	block, _ := pem.Decode(caKeyPEM)
	if block == nil {
		return nil, fmt.Errorf("ca.key is not PEM")
	}
	caKey, err := x509.ParseECPrivateKey(block.Bytes)
	if err != nil {
		return nil, err
	}
	return &server{
		pkiDir:    pkiDir,
		publicURL: strings.TrimRight(publicURL, "/"),
		caCert:    caCert,
		caCertPEM: string(caCertPEM),
		caKey:     caKey,
		tokens:    map[string]time.Time{},
	}, nil
}

func parseCertPEM(data []byte) (*x509.Certificate, error) {
	block, _ := pem.Decode(data)
	if block == nil {
		return nil, fmt.Errorf("not PEM")
	}
	return x509.ParseCertificate(block.Bytes)
}

func (s *server) serverTLSCert() (tls.Certificate, error) {
	return tls.LoadX509KeyPair(
		filepath.Join(s.pkiDir, "server.crt"),
		filepath.Join(s.pkiDir, "server.key"),
	)
}

// ---- admin listener -------------------------------------------------------

func (s *server) serveAdmin(addr string) error {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /", s.handleAdminPage)
	mux.HandleFunc("POST /enroll/begin", s.handleBegin)
	mux.HandleFunc("GET /qr", s.handleQR)
	mux.HandleFunc("POST /enroll/complete", s.handleComplete)

	cert, err := s.serverTLSCert()
	if err != nil {
		return err
	}
	srv := &http.Server{
		Addr:      addr,
		Handler:   mux,
		TLSConfig: &tls.Config{Certificates: []tls.Certificate{cert}},
	}
	return srv.ListenAndServeTLS("", "")
}

func (s *server) fingerprint() string {
	sum := sha256.Sum256(s.caCert.Raw)
	return "SHA256:" + hex.EncodeToString(sum[:])
}

func (s *server) handleBegin(w http.ResponseWriter, _ *http.Request) {
	buf := make([]byte, 9)
	if _, err := rand.Read(buf); err != nil {
		http.Error(w, "entropy failure", http.StatusInternalServerError)
		return
	}
	raw := strings.ToUpper(base64.RawURLEncoding.EncodeToString(buf))
	token := raw[:4] + "-" + raw[4:8] + "-" + raw[8:12]
	expires := time.Now().Add(tokenTTL)

	s.mu.Lock()
	s.tokens[token] = expires
	s.mu.Unlock()

	writeJSON(w, http.StatusOK, map[string]any{
		"v":              1,
		"url":            s.publicURL,
		"ca_fingerprint": s.fingerprint(),
		"ca_cert":        s.caCertPEM,
		"token":          token,
		"expires_at":     expires.Unix(),
	})
}

func (s *server) handleQR(w http.ResponseWriter, r *http.Request) {
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

type completeRequest struct {
	Token string `json:"token"`
	Label string `json:"label"`
	CSR   string `json:"csr"`
}

func (s *server) handleComplete(w http.ResponseWriter, r *http.Request) {
	var req completeRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid_json"})
		return
	}

	// Burn the token atomically before signing (SERVER_SPEC §7.5 step 2).
	s.mu.Lock()
	expiry, known := s.tokens[req.Token]
	if known {
		delete(s.tokens, req.Token)
	}
	s.mu.Unlock()
	switch {
	case !known:
		writeJSON(w, http.StatusForbidden, map[string]string{"error": "token_unknown"})
		return
	case time.Now().After(expiry):
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

	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 120))
	if err != nil {
		http.Error(w, "entropy failure", http.StatusInternalServerError)
		return
	}
	template := &x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: sanitizeLabel(req.Label)},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(deviceCertValidity),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, template, s.caCert, csr.PublicKey, s.caKey)
	if err != nil {
		http.Error(w, "signing failed", http.StatusInternalServerError)
		return
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})

	log.Printf("enrolled %q (serial 0x%s)", template.Subject.CommonName, serial.Text(16))
	writeJSON(w, http.StatusCreated, map[string]any{
		"certificate":    string(certPEM),
		"ca_certificate": s.caCertPEM,
		"serial":         "0x" + serial.Text(16),
		"expires_at":     template.NotAfter.Unix(),
	})
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

const adminPage = `<!doctype html><meta charset="utf-8">
<title>Sambaloader dev enrollment</title>
<style>body{font-family:sans-serif;max-width:40rem;margin:3rem auto;padding:0 1rem}
code{background:#eee;padding:.15rem .35rem;border-radius:4px}</style>
<h1>Sambaloader dev enrollment</h1>
<p><b>Never forward these ports.</b> Dev server only.</p>
<button id=b>Enroll a device</button>
<div id=out></div>
<script>
document.getElementById('b').onclick = async () => {
  const r = await fetch('/enroll/begin', {method:'POST'});
  const p = await r.json();
  const d = btoa(JSON.stringify(p)).replaceAll('+','-').replaceAll('/','_').replaceAll('=','');
  document.getElementById('out').innerHTML =
    '<p>Scan with the app, then confirm this fingerprint:</p>' +
    '<p><code>' + p.ca_fingerprint + '</code></p>' +
    '<img src="/qr?d=' + d + '" width=360 height=360>' +
    '<p>Token <code>' + p.token + '</code> expires in 10 minutes.</p>' +
    '<details><summary>Raw payload (debug paste)</summary><pre>' +
    JSON.stringify(p) .replace(/</g,'&lt;') + '</pre></details>';
};
</script>`

func (s *server) handleAdminPage(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_, _ = w.Write([]byte(adminPage))
}

// ---- mTLS API listener ----------------------------------------------------

func (s *server) serveAPI(addr string, lib *library) {
	pool := x509.NewCertPool()
	pool.AddCert(s.caCert)

	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{
			"version":     version,
			"device":      r.TLS.PeerCertificates[0].Subject.CommonName,
			"server_time": time.Now().Unix(),
		})
	})
	mux.HandleFunc("POST /api/v1/assets/check", lib.handleCheck)
	mux.HandleFunc("POST /api/v1/assets", func(w http.ResponseWriter, r *http.Request) {
		// Attribution header mirrors what nginx injects in production.
		r.Header.Set("X-Device-CN", r.TLS.PeerCertificates[0].Subject.CommonName)
		lib.handleUpload(w, r)
	})

	cert, err := s.serverTLSCert()
	if err != nil {
		log.Fatalf("api listener: %v", err)
	}
	srv := &http.Server{
		Addr:    addr,
		Handler: mux,
		TLSConfig: &tls.Config{
			Certificates: []tls.Certificate{cert},
			ClientAuth:   tls.RequireAndVerifyClientCert,
			ClientCAs:    pool,
			MinVersion:   tls.VersionTLS13,
		},
	}
	log.Fatal(srv.ListenAndServeTLS("", ""))
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}
