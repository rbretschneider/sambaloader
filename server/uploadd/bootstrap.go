// First-run CA provisioning (SERVER_SPEC §3).
//
// The whole point is that `docker compose up` is the entire setup. There
// is no bootstrap script to run, no openssl on the host, and nothing for
// the operator to copy anywhere: the CA, the server certificate and the
// empty CRL are generated here on first start if they are absent, and
// left strictly alone on every start after that.
//
// Trust still reaches the phone out of band — the enrollment QR carries
// the CA certificate *and* its fingerprint, and the app refuses a CA
// whose fingerprint does not match (§7.4). No file ever moves by hand.
package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/pem"
	"fmt"
	"log"
	"math/big"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// Validity per decision D1 — WireGuard-key semantics, no renewal flow.
const (
	caValidity     = 30 * 365 * 24 * time.Hour
	serverValidity = 10 * 365 * 24 * time.Hour
)

const (
	caKeyPerm  = 0o600
	caCertPerm = 0o644
)

// ensureCA provisions CA material into dir if it is not already there.
//
// Idempotent and non-destructive: if ca.crt exists, nothing is touched.
// Regenerating a CA would invalidate every enrolled device, so a partial
// or damaged directory is an error to report, never something to "fix"
// by overwriting.
func ensureCA(dir, hostname string, extraSANs []string) error {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return fmt.Errorf("create %s: %w", dir, err)
	}

	caCertPath := filepath.Join(dir, "ca.crt")
	caKeyPath := filepath.Join(dir, "ca.key")
	haveCert := fileExists(caCertPath)
	haveKey := fileExists(caKeyPath)

	switch {
	case haveCert && haveKey:
		return ensureServerCert(dir, hostname, extraSANs)
	case haveCert && !haveKey:
		// Deliberate offline-key operation: the operator moved ca.key
		// away. Enrollment will answer 503 until it comes back, which is
		// the documented behaviour, not a fault.
		log.Printf("CA present, ca.key absent — enrollment disabled until it is restored")
		return ensureServerCert(dir, hostname, extraSANs)
	case !haveCert && haveKey:
		return fmt.Errorf(
			"%s has ca.key but no ca.crt; refusing to guess — restore ca.crt or empty the directory",
			dir,
		)
	}

	log.Printf("no CA in %s — generating one (first run)", dir)
	caKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return fmt.Errorf("generate CA key: %w", err)
	}
	serial, err := randomSerial()
	if err != nil {
		return err
	}
	template := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: "Sambaloader CA"},
		NotBefore:             time.Now().Add(-time.Hour), // tolerate clock skew
		NotAfter:              time.Now().Add(caValidity),
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		BasicConstraintsValid: true,
		IsCA:                  true,
		MaxPathLen:            0,
		MaxPathLenZero:        true,
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &caKey.PublicKey, caKey)
	if err != nil {
		return fmt.Errorf("self-sign CA: %w", err)
	}
	if err := writeKey(caKeyPath, caKey); err != nil {
		return err
	}
	if err := writePEM(caCertPath, "CERTIFICATE", der, caCertPerm); err != nil {
		return err
	}
	if err := writeEmptyCRL(dir, caKey, der); err != nil {
		return err
	}
	log.Printf("CA created; fingerprint %s", fingerprintOf(der))
	return ensureServerCert(dir, hostname, extraSANs)
}

// ensureServerCert issues the TLS certificate nginx serves. Unlike the CA
// it IS reissued when the hostname changes, because a server certificate
// that does not cover the name being used simply fails to work — and the
// pinned trust anchor is the CA, so reissuing costs the phones nothing.
func ensureServerCert(dir, hostname string, extraSANs []string) error {
	certPath := filepath.Join(dir, "server.crt")
	keyPath := filepath.Join(dir, "server.key")

	if fileExists(certPath) && fileExists(keyPath) && certCovers(certPath, hostname, extraSANs) {
		return nil
	}
	caKey, caCert, err := loadCAKeyPair(dir)
	if err != nil {
		// No signing key (offline-key mode) and no usable server cert: the
		// stack cannot serve TLS at all, so say exactly that.
		return fmt.Errorf("cannot issue server certificate: %w", err)
	}
	log.Printf("issuing server certificate for %s", strings.Join(append([]string{hostname}, extraSANs...), ", "))

	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return fmt.Errorf("generate server key: %w", err)
	}
	serial, err := randomSerial()
	if err != nil {
		return err
	}
	template := &x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: hostname},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(serverValidity),
		KeyUsage:     x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	applySANs(template, append([]string{hostname}, extraSANs...))

	der, err := x509.CreateCertificate(rand.Reader, template, caCert, &key.PublicKey, caKey)
	if err != nil {
		return fmt.Errorf("sign server certificate: %w", err)
	}
	if err := writeKey(keyPath, key); err != nil {
		return err
	}
	return writePEM(certPath, "CERTIFICATE", der, caCertPerm)
}

// applySANs splits names into IP and DNS SANs. A certificate for a bare
// IP needs an IP SAN — a DNS entry holding "192.168.1.50" matches nothing.
func applySANs(template *x509.Certificate, names []string) {
	for _, name := range names {
		name = strings.TrimSpace(name)
		if name == "" {
			continue
		}
		if ip := net.ParseIP(name); ip != nil {
			template.IPAddresses = append(template.IPAddresses, ip)
			continue
		}
		template.DNSNames = append(template.DNSNames, name)
	}
}

// certCovers reports whether the existing certificate is still valid for
// every name in use, so a changed PUBLIC_URL or a new LAN IP triggers a
// reissue instead of an unexplained handshake failure.
func certCovers(certPath, hostname string, extraSANs []string) bool {
	raw, err := os.ReadFile(certPath)
	if err != nil {
		return false
	}
	block, _ := pem.Decode(raw)
	if block == nil {
		return false
	}
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return false
	}
	if time.Now().After(cert.NotAfter) {
		return false
	}
	for _, name := range append([]string{hostname}, extraSANs...) {
		name = strings.TrimSpace(name)
		if name == "" {
			continue
		}
		if cert.VerifyHostname(name) != nil {
			return false
		}
	}
	return true
}

func writeEmptyCRL(dir string, caKey *ecdsa.PrivateKey, caDER []byte) error {
	caCert, err := x509.ParseCertificate(caDER)
	if err != nil {
		return err
	}
	// An empty CRL, not a missing one: nginx's ssl_crl fails to load a
	// file that is not there, so the stack would not start without it.
	crl, err := x509.CreateRevocationList(rand.Reader, &x509.RevocationList{
		Number:     big.NewInt(1),
		ThisUpdate: time.Now().Add(-time.Hour),
		NextUpdate: time.Now().Add(caValidity),
	}, caCert, caKey)
	if err != nil {
		return fmt.Errorf("create empty CRL: %w", err)
	}
	return writePEM(filepath.Join(dir, "crl.pem"), "X509 CRL", crl, caCertPerm)
}

func loadCAKeyPair(dir string) (*ecdsa.PrivateKey, *x509.Certificate, error) {
	certPEM, err := os.ReadFile(filepath.Join(dir, "ca.crt"))
	if err != nil {
		return nil, nil, err
	}
	certBlock, _ := pem.Decode(certPEM)
	if certBlock == nil {
		return nil, nil, fmt.Errorf("ca.crt is not PEM")
	}
	cert, err := x509.ParseCertificate(certBlock.Bytes)
	if err != nil {
		return nil, nil, err
	}
	keyPEM, err := os.ReadFile(filepath.Join(dir, "ca.key"))
	if err != nil {
		return nil, nil, fmt.Errorf("ca.key unavailable: %w", err)
	}
	keyBlock, _ := pem.Decode(keyPEM)
	if keyBlock == nil {
		return nil, nil, fmt.Errorf("ca.key is not PEM")
	}
	key, err := x509.ParseECPrivateKey(keyBlock.Bytes)
	if err != nil {
		return nil, nil, err
	}
	return key, cert, nil
}

func randomSerial() (*big.Int, error) {
	limit := new(big.Int).Lsh(big.NewInt(1), 128)
	serial, err := rand.Int(rand.Reader, limit)
	if err != nil {
		return nil, fmt.Errorf("serial: %w", err)
	}
	return serial, nil
}

func writeKey(path string, key *ecdsa.PrivateKey) error {
	der, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return err
	}
	return writePEM(path, "EC PRIVATE KEY", der, caKeyPerm)
}

func writePEM(path, blockType string, der []byte, perm os.FileMode) error {
	encoded := pem.EncodeToMemory(&pem.Block{Type: blockType, Bytes: der})
	if err := os.WriteFile(path, encoded, perm); err != nil {
		return fmt.Errorf("write %s: %w", path, err)
	}
	return nil
}

func fileExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && !info.IsDir()
}

// fingerprintOf formats a DER certificate's SHA-256 the same way the
// enrollment QR and the app's confirmation screen do, so the operator can
// compare the two strings character for character.
func fingerprintOf(der []byte) string {
	sum := sha256.Sum256(der)
	return "SHA256:" + hex.EncodeToString(sum[:])
}

// hostnameFromURL pulls the SAN name out of PUBLIC_URL so the operator
// configures the hostname exactly once.
func hostnameFromURL(publicURL string) string {
	parsed, err := url.Parse(publicURL)
	if err != nil || parsed.Hostname() == "" {
		return "localhost"
	}
	return parsed.Hostname()
}
