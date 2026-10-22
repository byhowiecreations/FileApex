package main

import "testing"

func TestPairSasMatchesTheApp(t *testing.T) {
	// The same vectors are asserted in PairingSasTest.kt.
	cases := []struct{ host, joiner, want string }{
		{"aabbcc", "ddeeff", "077 390"},
		{"ddeeff", "aabbcc", "914 018"},
	}
	for _, c := range cases {
		if got := pairSasCode(c.host, c.joiner, "host-id", "join-id", "0a1b", "2c3d"); got != c.want {
			t.Errorf("pairSasCode(%q,%q) = %q, want %q", c.host, c.joiner, got, c.want)
		}
	}
	if got := pairCommit("2c3d", "ddeeff", "join-id"); got != "16d116308cc6901b6ede677d8ec639ab097a9b8d04d709854dc0c163cd370945" {
		t.Errorf("pairCommit = %q", got)
	}
}
