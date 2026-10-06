package tsnetbridge

import (
	"fmt"
	"os"
	"path/filepath"
)

// prepareNodeStorage makes the node directory usable before tsnet starts.
// LocalBackend calls logpolicy.LogsDir, which panics when every fallback
// directory is unusable. Android has no HOME, its working directory is "/",
// and the default temp directory is not writable for an app.
func prepareNodeStorage(dir string) error {
	if dir == "" {
		return fmt.Errorf("missing state directory")
	}
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	logs := filepath.Join(dir, "logs")
	if err := os.MkdirAll(logs, 0700); err != nil {
		return err
	}
	if err := os.Setenv("TS_LOGS_DIR", logs); err != nil {
		return err
	}
	return preparePlatformStorage(dir)
}

func removeDirContents(dir string) error {
	entries, err := os.ReadDir(dir)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	for _, entry := range entries {
		if err := os.RemoveAll(filepath.Join(dir, entry.Name())); err != nil {
			return err
		}
	}
	return nil
}
