package tsnetbridge

import (
	"net/netip"
	"strconv"
	"strings"
)

// lanPrefixBits is the mask width ConnectivityManager reported for this address.
// A missing or impossible width falls back to a host mask. A host mask never
// contains the LAN gateway, so port mapping cannot see the router.
func lanPrefixBits(ip netip.Addr, prefixText string) int {
	bits := ip.BitLen()
	parsed, err := strconv.Atoi(strings.TrimSpace(prefixText))
	if err != nil || parsed <= 0 || parsed > bits {
		return bits
	}
	return parsed
}
