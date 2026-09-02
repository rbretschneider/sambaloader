// uploadd — the Sambaloader upload service (SERVER_SPEC v1.1).
//
// Plain HTTP behind nginx on the internal bridge. nginx has already
// enforced mutual TLS for /api/* and injects X-Device-CN; this service
// performs no authentication of its own beyond requiring that header
// (its absence means the mTLS layer was bypassed — a misconfiguration).
//
// Responsibilities (exhaustive, SERVER_SPEC §6.1): enrollment endpoints,
// device registry, dedup + library writes. Nothing else.
package main

import (
	"flag"
	"log"
	"net/http"
	"os"
	"strings"
)

type config struct {
	listen      string
	libraryPath string
	dbPath      string
	caDir       string
	publicURL   string
	// Additional SANs for the server certificate — typically the LAN IP,
	// so the admin listener works before DNS does.
	extraSANs []string
}

func configFromEnv() config {
	return config{
		listen:      envOr("LISTEN", ":8080"),
		libraryPath: envOr("LIBRARY_PATH", "/library"),
		dbPath:      envOr("DB_PATH", "/state/uploadd.db"),
		caDir:       envOr("CA_DIR", "/ca"),
		publicURL:   envOr("PUBLIC_URL", "https://localhost"),
		extraSANs:   splitList(os.Getenv("EXTRA_SANS")),
	}
}

func splitList(value string) []string {
	var out []string
	for _, part := range strings.Split(value, ",") {
		if trimmed := strings.TrimSpace(part); trimmed != "" {
			out = append(out, trimmed)
		}
	}
	return out
}

func envOr(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

func main() {
	revokeSerial := flag.String("revoke", "", "revoke a device certificate by serial (e.g. 0x4a2f...) and regenerate the CRL, then exit")
	pair := flag.Bool("pair", false, "print a pairing QR code to the terminal and exit")
	flag.Parse()

	cfg := configFromEnv()
	db, err := openDB(cfg.dbPath)
	if err != nil {
		log.Fatalf("database: %v", err)
	}
	defer db.Close()

	if *revokeSerial != "" {
		if err := revokeDevice(db, cfg, *revokeSerial); err != nil {
			log.Fatalf("revoke: %v", err)
		}
		return
	}

	// First run provisions its own CA, server certificate and empty CRL.
	// `docker compose up` is the whole setup — there is no script to run
	// and nothing for the operator to copy anywhere.
	if err := ensureCA(cfg.caDir, hostnameFromURL(cfg.publicURL), cfg.extraSANs); err != nil {
		log.Fatalf("CA provisioning: %v", err)
	}

	ca, err := loadCA(cfg.caDir)
	if err != nil {
		log.Fatalf("CA material: %v (is %s mounted?)", err, cfg.caDir)
	}

	if *pair {
		if err := printPairingQR(db, ca, cfg); err != nil {
			log.Fatalf("pair: %v", err)
		}
		return
	}

	store := newLocalFSStore(cfg.libraryPath)
	server := &apiServer{db: db, store: store, ca: ca, publicURL: cfg.publicURL}

	mux := http.NewServeMux()
	// mTLS-guarded routes (nginx :443 → here). requireDeviceCN rejects
	// direct access that bypassed the proxy.
	mux.HandleFunc("GET /api/v1/health", requireDeviceCN(server.handleHealth))
	mux.HandleFunc("POST /api/v1/assets/check", requireDeviceCN(server.handleCheck))
	mux.HandleFunc("POST /api/v1/assets", requireDeviceCN(server.handleUpload))

	// Enrollment/admin routes (nginx :8443 → here; NEVER exposed on :443
	// because api.conf proxies only /api/).
	mux.HandleFunc("POST /enroll/begin", server.handleEnrollBegin)
	mux.HandleFunc("POST /enroll/complete", server.handleEnrollComplete)
	mux.HandleFunc("GET /qr", server.handleQR)
	mux.HandleFunc("GET /admin/devices", server.handleDeviceList)
	mux.HandleFunc("POST /admin/devices/{serial}/revoke", server.handleRevoke)

	log.Printf("uploadd listening on %s (library=%s db=%s)", cfg.listen, cfg.libraryPath, cfg.dbPath)
	log.Fatal(http.ListenAndServe(cfg.listen, mux))
}

// requireDeviceCN enforces SERVER_SPEC §6.1: a missing X-Device-CN on an
// /api/ route means the request did not come through nginx's mTLS layer.
func requireDeviceCN(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Device-CN") == "" {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthenticated"})
			return
		}
		next(w, r)
	}
}
