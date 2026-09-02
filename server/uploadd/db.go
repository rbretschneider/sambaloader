// SQLite persistence (SERVER_SPEC §6.2/§6.3). Pure-Go driver so the binary
// stays static and the container stays FROM scratch.
package main

import (
	"database/sql"
	"fmt"
	"os"
	"path/filepath"

	_ "modernc.org/sqlite"
)

const schema = `
CREATE TABLE IF NOT EXISTS devices (
  serial        TEXT PRIMARY KEY,
  cn            TEXT NOT NULL,
  label         TEXT NOT NULL,
  enrolled_at   INTEGER NOT NULL,
  last_seen_at  INTEGER,
  revoked_at    INTEGER,
  bytes_total   INTEGER NOT NULL DEFAULT 0,
  assets_total  INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS pairing_tokens (
  token       TEXT PRIMARY KEY,
  created_at  INTEGER NOT NULL,
  expires_at  INTEGER NOT NULL,
  used_at     INTEGER
);

-- Small key/value store for values that must survive restarts but have
-- no table of their own — currently the generated admin password.
CREATE TABLE IF NOT EXISTS settings (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS assets (
  sha256      TEXT PRIMARY KEY,
  path        TEXT NOT NULL,
  size        INTEGER NOT NULL,
  device_cn   TEXT NOT NULL,
  received_at INTEGER NOT NULL
);
`

func openDB(path string) (*sql.DB, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return nil, err
	}
	db, err := sql.Open("sqlite", path+"?_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)")
	if err != nil {
		return nil, err
	}
	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, fmt.Errorf("schema: %w", err)
	}
	return db, nil
}

// burnToken marks the token used atomically BEFORE signing (SERVER_SPEC
// §7.5 step 2): a race burns the token rather than double-signing.
func burnToken(db *sql.DB, token string, now int64) (expired bool, ok bool, err error) {
	result, err := db.Exec(
		"UPDATE pairing_tokens SET used_at = ? WHERE token = ? AND used_at IS NULL",
		now, token,
	)
	if err != nil {
		return false, false, err
	}
	rows, err := result.RowsAffected()
	if err != nil || rows == 0 {
		return false, false, err
	}
	var expiresAt int64
	if err := db.QueryRow(
		"SELECT expires_at FROM pairing_tokens WHERE token = ?", token,
	).Scan(&expiresAt); err != nil {
		return false, false, err
	}
	return now > expiresAt, true, nil
}

func tokenExists(db *sql.DB, token string) (bool, error) {
	var one int
	err := db.QueryRow("SELECT 1 FROM pairing_tokens WHERE token = ?", token).Scan(&one)
	if err == sql.ErrNoRows {
		return false, nil
	}
	return err == nil, err
}

func recordUpload(db *sql.DB, deviceCN, sha256, path string, size, now int64) error {
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.Exec(
		"INSERT INTO assets (sha256, path, size, device_cn, received_at) VALUES (?, ?, ?, ?, ?)",
		sha256, path, size, deviceCN, now,
	); err != nil {
		return err
	}
	if _, err := tx.Exec(
		"UPDATE devices SET bytes_total = bytes_total + ?, assets_total = assets_total + 1, last_seen_at = ? WHERE cn = ?",
		size, now, deviceCN,
	); err != nil {
		return err
	}
	return tx.Commit()
}

func touchDevice(db *sql.DB, deviceCN string, now int64) {
	// Best-effort bookkeeping; failure must not fail the request.
	_, _ = db.Exec("UPDATE devices SET last_seen_at = ? WHERE cn = ?", now, deviceCN)
}
