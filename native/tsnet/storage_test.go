package tsnetbridge

import (
	"os"
	"path/filepath"
	"testing"
)

func TestPrepareNodeStorageCreatesLogDir(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("TS_LOGS_DIR", "")
	if err := prepareNodeStorage(dir); err != nil {
		t.Fatal(err)
	}
	logs := os.Getenv("TS_LOGS_DIR")
	if logs != filepath.Join(dir, "logs") {
		t.Fatalf("TS_LOGS_DIR = %q", logs)
	}
	info, err := os.Stat(logs)
	if err != nil {
		t.Fatal(err)
	}
	if !info.IsDir() {
		t.Fatalf("%s is not a directory", logs)
	}
	if err := prepareNodeStorage(""); err == nil {
		t.Fatal("empty state directory was accepted")
	}
}
