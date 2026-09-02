// Terminal pairing (`uploadd -pair`).
//
// The admin web page still exists, but reaching it means knowing the LAN
// IP and clicking past a browser warning about the private CA. Printing
// the same QR straight into the terminal the operator already has open
// makes the documented path "compose up, compose exec, scan" with no
// browser involved at all.
package main

import (
	"crypto/rand"
	"database/sql"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	qrcode "github.com/skip2/go-qrcode"
)

// printPairingQR mints a one-time token and renders the enrollment
// payload as a QR block on stdout. Identical payload to POST
// /enroll/begin (SERVER_SPEC §7.4) — the CA certificate and its
// fingerprint travel inside it, which is what keeps the operator from
// ever copying a file to the phone.
func printPairingQR(db *sql.DB, ca *caMaterial, cfg config) error {
	token, expires, err := mintPairingToken(db)
	if err != nil {
		return err
	}
	payload, err := json.Marshal(map[string]any{
		"v":              1,
		"url":            strings.TrimRight(cfg.publicURL, "/"),
		"ca_fingerprint": ca.fingerprint(),
		"ca_cert":        ca.certPEM,
		"token":          token,
		"expires_at":     expires.Unix(),
	})
	if err != nil {
		return err
	}
	code, err := qrcode.New(string(payload), qrcode.Low)
	if err != nil {
		return fmt.Errorf("qr encode: %w", err)
	}
	// Low recovery + half-block rendering: the payload carries a whole
	// PEM certificate, and at higher recovery levels the result is too
	// wide to fit an 80-column terminal.
	fmt.Println()
	fmt.Print(code.ToSmallString(false))
	fmt.Println()
	fmt.Printf("  Server:      %s\n", strings.TrimRight(cfg.publicURL, "/"))
	fmt.Printf("  Code:        %s\n", token)
	fmt.Printf("  Fingerprint: %s\n", ca.fingerprint())
	fmt.Printf("  Expires:     %s (%.0f minutes)\n",
		expires.Format(time.RFC3339), time.Until(expires).Minutes())
	fmt.Println()
	fmt.Println("  Scan this in the Sambaloader app, then check the fingerprint above")
	fmt.Println("  matches the one the app shows before confirming.")
	fmt.Println()
	return nil
}

// pairingAlphabet omits characters that get misread or mistyped: I/1,
// O/0, L, U. The token is grouped with hyphens for legibility, so the
// alphabet must not contain one itself — base64url does, which produced
// codes like "20XK-EHM--LKHH".
const pairingAlphabet = "ABCDEFGHJKMNPQRSTVWXYZ23456789"

func mintPairingToken(db *sql.DB) (string, time.Time, error) {
	token, err := randomToken()
	if err != nil {
		return "", time.Time{}, err
	}
	now := time.Now()
	expires := now.Add(tokenTTL)
	if _, err := db.Exec(
		"INSERT INTO pairing_tokens (token, created_at, expires_at) VALUES (?, ?, ?)",
		token, now.Unix(), expires.Unix(),
	); err != nil {
		return "", time.Time{}, fmt.Errorf("store token: %w", err)
	}
	return token, expires, nil
}

// randomToken builds XXXX-XXXX-XXXX by rejection-free indexing into
// pairingAlphabet. ~59 bits over a 10-minute single-use window.
func randomToken() (string, error) {
	const groups, groupSize = 3, 4
	buf := make([]byte, groups*groupSize)
	if _, err := rand.Read(buf); err != nil {
		return "", fmt.Errorf("entropy: %w", err)
	}
	var b strings.Builder
	for i, v := range buf {
		if i > 0 && i%groupSize == 0 {
			b.WriteByte('-')
		}
		b.WriteByte(pairingAlphabet[int(v)%len(pairingAlphabet)])
	}
	return b.String(), nil
}
