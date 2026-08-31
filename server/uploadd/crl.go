// Revocation (SERVER_SPEC §3.4): mark the device revoked, regenerate
// crl.pem from every revoked serial in the registry, signed with ca.key
// (temporarily restored, same as enrollment). nginx reloads the CRL and
// the device dies at its next handshake.
package main

import (
	"crypto/rand"
	"crypto/x509"
	"database/sql"
	"encoding/pem"
	"errors"
	"fmt"
	"log"
	"math/big"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const crlValidity = 10 * 365 * 24 * time.Hour

// errCaKeyAbsent lets the HTTP layer answer 503 with actionable guidance
// instead of a generic failure: the operator must restore ca.key.
var errCaKeyAbsent = errors.New("ca.key is not present")

// errUnknownDevice maps to 404.
var errUnknownDevice = errors.New("no such device")

func revokeDevice(db *sql.DB, cfg config, serialHex string) error {
	ca, err := loadCA(cfg.caDir)
	if err != nil {
		return fmt.Errorf("CA material: %w", err)
	}
	return revokeWithCA(db, ca, cfg.caDir, serialHex)
}

func revokeWithCA(db *sql.DB, ca *caMaterial, caDir, serialHex string) error {
	caKey, err := ca.key()
	if err != nil {
		return errCaKeyAbsent
	}

	now := time.Now()
	result, err := db.Exec(
		"UPDATE devices SET revoked_at = ? WHERE serial = ? AND revoked_at IS NULL",
		now.Unix(), serialHex,
	)
	if err != nil {
		return err
	}
	if rows, _ := result.RowsAffected(); rows == 0 {
		var one int
		if db.QueryRow("SELECT 1 FROM devices WHERE serial = ?", serialHex).Scan(&one) == sql.ErrNoRows {
			return errUnknownDevice
		}
		log.Printf("device %s was already revoked; regenerating CRL anyway", serialHex)
	}

	rows, err := db.Query("SELECT serial, revoked_at FROM devices WHERE revoked_at IS NOT NULL")
	if err != nil {
		return err
	}
	defer rows.Close()

	var revoked []x509.RevocationListEntry
	for rows.Next() {
		var serial string
		var revokedAt int64
		if err := rows.Scan(&serial, &revokedAt); err != nil {
			return err
		}
		number, ok := new(big.Int).SetString(strings.TrimPrefix(serial, "0x"), 16)
		if !ok {
			return fmt.Errorf("unparseable serial in registry: %s", serial)
		}
		revoked = append(revoked, x509.RevocationListEntry{
			SerialNumber:   number,
			RevocationTime: time.Unix(revokedAt, 0),
		})
	}

	crlNumber := big.NewInt(now.Unix())
	der, err := x509.CreateRevocationList(rand.Reader, &x509.RevocationList{
		RevokedCertificateEntries: revoked,
		Number:                    crlNumber,
		ThisUpdate:                now,
		NextUpdate:                now.Add(crlValidity),
	}, ca.cert, caKey)
	if err != nil {
		return fmt.Errorf("CRL generation: %w", err)
	}

	crlPath := filepath.Join(caDir, "crl.pem")
	pemBytes := pem.EncodeToMemory(&pem.Block{Type: "X509 CRL", Bytes: der})
	if err := os.WriteFile(crlPath, pemBytes, 0o644); err != nil {
		return err
	}
	log.Printf("revoked %s; wrote %s with %d entries. Reload nginx to apply.", serialHex, crlPath, len(revoked))
	return nil
}
