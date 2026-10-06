//go:build android

package tsnetbridge

import (
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"sync/atomic"

	"tailscale.com/net/netmon"
)

// Android app UIDs cannot open a NETLINK_ROUTE socket, so net.Interfaces
// fails during tsnet startup. The address is supplied by ConnectivityManager
// before Start. Nothing here dials or binds a netlink socket.
var (
	androidLinkName   atomic.Value // string
	androidLinkIP     atomic.Value // string
	androidLinkPrefix atomic.Value // string
)

func init() {
	androidLinkName.Store("")
	androidLinkIP.Store("")
	androidLinkPrefix.Store("")
	netmon.RegisterInterfaceGetter(androidInterfaces)
}

// preparePlatformStorage points Go's home, cache, and temp lookups at the
// node directory. Those variables are unset in an Android app process.
func preparePlatformStorage(dir string) error {
	cache := filepath.Join(dir, "cache")
	tmp := filepath.Join(dir, "tmp")
	if err := os.MkdirAll(cache, 0700); err != nil {
		return err
	}
	if err := os.MkdirAll(tmp, 0700); err != nil {
		return err
	}
	if err := os.Setenv("HOME", dir); err != nil {
		return err
	}
	if err := os.Setenv("XDG_CACHE_HOME", cache); err != nil {
		return err
	}
	return os.Setenv("TMPDIR", tmp)
}

// SetLinkAddress records the active interface before the node starts.
// prefix is the LinkAddress prefix length, such as 24. Android netmon only
// learns the default interface from this process, because app UIDs cannot
// read the route socket.
func SetLinkAddress(name, ip, prefix string) {
	androidLinkName.Store(name)
	androidLinkIP.Store(ip)
	androidLinkPrefix.Store(prefix)
	if name != "" {
		netmon.UpdateLastKnownDefaultRouteInterface(name)
	}
}

// NotifyLinkChange asks the running monitor to re-read the registered getter.
// Android app processes cannot receive netlink route events. InjectEvent is
// the hook the Android client uses after ConnectivityManager reports a change.
func NotifyLinkChange() {
	mu.Lock()
	node := current
	mu.Unlock()
	if node == nil || node.server == nil {
		return
	}
	sys := node.server.Sys()
	if sys == nil {
		return
	}
	monitor, ok := sys.NetMon.GetOK()
	if !ok || monitor == nil {
		return
	}
	monitor.InjectEvent()
}

func androidInterfaces() ([]netmon.Interface, error) {
	name, _ := androidLinkName.Load().(string)
	ipText, _ := androidLinkIP.Load().(string)
	prefixText, _ := androidLinkPrefix.Load().(string)
	if ip, err := netip.ParseAddr(ipText); err == nil && ip.IsValid() && !ip.IsLoopback() && !ip.IsUnspecified() {
		if name == "" {
			name = "android"
		}
		bits := lanPrefixBits(ip, prefixText)
		return []netmon.Interface{{
			Interface: &net.Interface{
				Index: 1,
				MTU:   1500,
				Name:  name,
				Flags: net.FlagUp | net.FlagRunning,
			},
			AltAddrs: []net.Addr{
				&net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(bits, ip.BitLen())},
			},
		}}, nil
	}
	return []netmon.Interface{{
		Interface: &net.Interface{
			Index: 1,
			MTU:   65536,
			Name:  "lo",
			Flags: net.FlagUp | net.FlagLoopback | net.FlagRunning,
		},
		AltAddrs: []net.Addr{
			&net.IPNet{IP: net.IPv4(127, 0, 0, 1), Mask: net.CIDRMask(8, 32)},
		},
	}}, nil
}
