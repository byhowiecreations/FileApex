package main

import "testing"

func TestHasRoomForSmallAndHugeUploads(t *testing.T) {
	dir := t.TempDir()
	if !hasRoomFor(dir+"/new/deeper", 1024) {
		t.Fatal("a small upload must fit")
	}
	if hasRoomFor(dir, 1<<62) {
		t.Fatal("an upload larger than the volume must not fit")
	}
}
