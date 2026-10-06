//go:build darwin

package tsnetbridge

import "tailscale.com/net/netns"

func init() {
	// macOS Local Network privacy drops UDP pinned with IP_BOUND_IF, including
	// to a peer on the same LAN. Leaving the socket unbound lets the OS route
	// that destination, which is how a direct path can replace DERP.
	netns.SetDisableBindConnToInterface(func(string, ...any) {}, true)
}
