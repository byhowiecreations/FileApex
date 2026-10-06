package tsnetbridge

import (
	"net/netip"
	"testing"
)

func TestLanPrefixKeepsTheGateway(t *testing.T) {
	ip := netip.MustParseAddr("172.16.16.218")
	gateway := netip.MustParseAddr("172.16.16.1")
	hostBits := lanPrefixBits(ip, "")
	if hostBits != 32 {
		t.Fatalf("missing prefix = %d", hostBits)
	}
	if netip.PrefixFrom(ip, hostBits).Contains(gateway) {
		t.Fatal("host mask contained the gateway")
	}
	lanBits := lanPrefixBits(ip, "24")
	if lanBits != 24 {
		t.Fatalf("lan prefix = %d", lanBits)
	}
	if !netip.PrefixFrom(ip, lanBits).Contains(gateway) {
		t.Fatal("lan prefix did not contain the gateway")
	}
	if lanPrefixBits(ip, "0") != 32 || lanPrefixBits(ip, "99") != 32 || lanPrefixBits(ip, "nope") != 32 {
		t.Fatal("impossible prefix was accepted")
	}
}
