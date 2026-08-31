// LocalFSStore — the one v1 Store implementation (SERVER_SPEC §6.3–§6.5).
package main

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"time"
)

// Store is the storage seam (SERVER_SPEC §6.3): a future WebDAV/S3 backend
// drops in here without touching the HTTP handlers. v1 ships LocalFSStore
// only — do not build others.
type Store interface {
	Put(declaredSha256 string, meta AssetMeta, r io.Reader) (path string, err error)
}

type AssetMeta struct {
	CapturedAt time.Time
	Filename   string
}

// errHashMismatch maps to HTTP 409: the body did not hash to the declared
// value; the temp file is discarded.
var errHashMismatch = fmt.Errorf("body hash does not match declared hash")

type localFSStore struct {
	root string
}

func newLocalFSStore(root string) *localFSStore {
	return &localFSStore{root: root}
}

// Put streams the body into <root>/.incoming, verifies the hash, fsyncs,
// then renames into the dated layout. A partial or mismatched upload never
// leaves .incoming — Samba consumers never see a truncated file. On an
// SMB-mounted library the rename is not atomic; the README mandates
// excluding .incoming/ from the share (SERVER_SPEC §1.1).
func (s *localFSStore) Put(declaredSha256 string, meta AssetMeta, r io.Reader) (string, error) {
	incoming := filepath.Join(s.root, ".incoming")
	if err := os.MkdirAll(incoming, 0o755); err != nil {
		return "", err
	}
	temp, err := os.CreateTemp(incoming, "upload-*.part")
	if err != nil {
		return "", err
	}
	defer os.Remove(temp.Name())

	hasher := sha256.New()
	if _, err := io.Copy(io.MultiWriter(temp, hasher), r); err != nil {
		temp.Close()
		return "", err
	}
	if err := temp.Sync(); err != nil {
		temp.Close()
		return "", err
	}
	if err := temp.Close(); err != nil {
		return "", err
	}

	actual := hex.EncodeToString(hasher.Sum(nil))
	if actual != declaredSha256 {
		return "", errHashMismatch
	}

	relative := datedPath(meta, actual)
	final := filepath.Join(s.root, relative)
	if err := os.MkdirAll(filepath.Dir(final), 0o755); err != nil {
		return "", err
	}
	if err := os.Rename(temp.Name(), final); err != nil {
		return "", err
	}
	syncDir(filepath.Dir(final))
	return relative, nil
}

// photos/YYYY/MM/YYYY-MM-DD_HHMMSS_<hash6><ext> (SERVER_SPEC §6.5).
func datedPath(meta AssetMeta, sha string) string {
	when := meta.CapturedAt.UTC()
	if when.IsZero() || when.Unix() <= 0 {
		when = time.Now().UTC()
	}
	ext := filepath.Ext(meta.Filename)
	if ext == "" {
		ext = ".bin"
	}
	return filepath.Join(
		"photos",
		when.Format("2006"),
		when.Format("01"),
		fmt.Sprintf("%s_%s%s", when.Format("2006-01-02_150405"), sha[:6], ext),
	)
}

func syncDir(dir string) {
	// Best effort: durability of the rename itself. Not all mounts
	// support directory fsync; failure is not worth failing the upload.
	if handle, err := os.Open(dir); err == nil {
		_ = handle.Sync()
		handle.Close()
	}
}
