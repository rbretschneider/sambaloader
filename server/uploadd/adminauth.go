// Admin-listener authentication.
//
// Port 8443 carries enrollment: anything that can reach it can add a
// device and upload into the library. Before this, the only control was
// "do not forward the port", which makes the trust boundary the whole
// LAN — guests, IoT gear, anything compromised on the wifi.
//
// The check lives here rather than in nginx on purpose. It then applies
// even to someone who runs the image behind their own proxy, or wires
// nginx up wrong, which is exactly the person this protects.
package main

import (
	"crypto/subtle"
	"database/sql"
	"errors"
	"fmt"
	"log"
	"net/http"
)

const adminPasswordKey = "admin_password"

// resolveAdminPassword returns the password the admin listener will
// require, generating and persisting one on first run.
//
// The generated path is the important one: leaving ADMIN_PASSWORD blank
// yields a strong random password printed to the log, not an open door
// and not "admin". The lazy option has to be the safe option, because the
// lazy option is the one that gets used.
func resolveAdminPassword(db *sql.DB, configured string) (password string, generated bool, err error) {
	if configured != "" {
		return configured, false, nil
	}
	stored, err := readSetting(db, adminPasswordKey)
	if err != nil {
		return "", false, err
	}
	if stored != "" {
		return stored, true, nil
	}
	// Same shape as a pairing code: unambiguous characters, easy to read
	// off a log and type into a browser prompt.
	fresh, err := randomToken()
	if err != nil {
		return "", false, err
	}
	if err := writeSetting(db, adminPasswordKey, fresh); err != nil {
		return "", false, err
	}
	return fresh, true, nil
}

// announceAdminPassword prints the credential every start, because the
// operator most likely to need it is the one clicking through Portainer
// with no shell — container logs are the one place they will look.
func announceAdminPassword(password string, generated bool) {
	if !generated {
		log.Printf("admin listener: password from ADMIN_PASSWORD")
		return
	}
	log.Printf("admin listener: username %q password %q", adminUsername, password)
	log.Printf("admin listener: set ADMIN_PASSWORD in .env to choose your own")
}

const adminUsername = "admin"

// requireAdminAuth gates the enrollment and admin routes with HTTP Basic,
// so a browser simply prompts.
func requireAdminAuth(password string, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user, pass, ok := r.BasicAuth()
		// Compare both halves in constant time and always compare both,
		// so a wrong username cannot be distinguished by timing.
		userOK := subtle.ConstantTimeCompare([]byte(user), []byte(adminUsername)) == 1
		passOK := subtle.ConstantTimeCompare([]byte(pass), []byte(password)) == 1
		if !ok || !userOK || !passOK {
			w.Header().Set("WWW-Authenticate", `Basic realm="Sambaloader admin", charset="UTF-8"`)
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
			return
		}
		next(w, r)
	}
}

func readSetting(db *sql.DB, key string) (string, error) {
	var value string
	err := db.QueryRow("SELECT value FROM settings WHERE key = ?", key).Scan(&value)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	if err != nil {
		return "", fmt.Errorf("read setting %s: %w", key, err)
	}
	return value, nil
}

func writeSetting(db *sql.DB, key, value string) error {
	if _, err := db.Exec(
		"INSERT INTO settings (key, value) VALUES (?, ?) "+
			"ON CONFLICT(key) DO UPDATE SET value = excluded.value",
		key, value,
	); err != nil {
		return fmt.Errorf("write setting %s: %w", key, err)
	}
	return nil
}
