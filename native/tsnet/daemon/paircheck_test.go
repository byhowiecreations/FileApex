package main

import "testing"

func TestPairCheckCodeMatchesTheApp(t *testing.T) {
	// The same vectors are asserted in PairingCheckCodeTest.kt.
	cases := []struct{ host, joiner, code, want string }{
		{"aabbcc", "ddeeff", "123456", "862 634"},
		{"aabbcc", "ddeeff", "123 456", "862 634"},
		{"ddeeff", "aabbcc", "123456", "463 093"},
		{"0123456789abcdef", "fedcba9876543210", "000001", "962 314"},
	}
	for _, c := range cases {
		if got := pairCheckCode(c.host, c.joiner, c.code); got != c.want {
			t.Errorf("pairCheckCode(%q,%q,%q) = %q, want %q", c.host, c.joiner, c.code, got, c.want)
		}
	}
}

func TestCheckKeyFallsBackToTheDeviceID(t *testing.T) {
	if checkKey(" ABC123 ", "dev") != "abc123" || checkKey("", " dev ") != "dev" {
		t.Error("pairKey did not follow the app's rule")
	}
}
