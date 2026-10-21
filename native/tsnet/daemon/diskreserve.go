package main

import (
	"os"
	"path/filepath"
)

// diskReserveBytes stays free on the receiving volume after an upload, so one transfer cannot fill the disk.
const diskReserveBytes = 256 << 20

func hasRoomFor(dir string, incoming int64) bool {
	for dir != "" && dir != "." {
		if _, err := os.Stat(dir); err == nil {
			break
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			break
		}
		dir = parent
	}
	_, free, ok := diskUsage(dir)
	if !ok || free <= 0 {
		return true
	}
	if incoming < 0 {
		incoming = 0
	}
	return incoming+diskReserveBytes <= free
}
