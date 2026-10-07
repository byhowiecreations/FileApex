//go:build !(linux || darwin)

package main

func diskUsage(path string) (total, free int64, ok bool) {
	return 0, 0, false
}
