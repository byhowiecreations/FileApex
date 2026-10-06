package main

import (
	"net"
	"os"
	"strconv"
	"strings"
)

func isPrivateLAN(ip string) bool {
	parsed := net.ParseIP(strings.TrimSpace(ip)).To4()
	if parsed == nil || parsed.IsLoopback() || parsed.IsLinkLocalUnicast() {
		return false
	}
	switch {
	case parsed[0] == 10:
		return true
	case parsed[0] == 192 && parsed[1] == 168:
		return true
	case parsed[0] == 172 && parsed[1] >= 16 && parsed[1] <= 31:
		return true
	default:
		return false
	}
}

func isContainerBridge(name string) bool {
	switch name {
	case "docker0", "cni0", "flannel.1", "virbr0":
		return true
	}
	if strings.HasPrefix(name, "veth") || strings.HasPrefix(name, "docker") {
		return true
	}
	if rest, ok := strings.CutPrefix(name, "br-"); ok && len(rest) >= 12 && isHex(rest) {
		return true
	}
	return false
}

func isHex(s string) bool {
	for _, r := range s {
		switch {
		case r >= '0' && r <= '9', r >= 'a' && r <= 'f', r >= 'A' && r <= 'F':
		default:
			return false
		}
	}
	return s != ""
}

func ifaceName(ip string) string {
	target := net.ParseIP(strings.TrimSpace(ip))
	if target == nil {
		return ""
	}
	ifaces, err := net.Interfaces()
	if err != nil {
		return ""
	}
	for _, ifi := range ifaces {
		addrs, err := ifi.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			ipnet, ok := addr.(*net.IPNet)
			if ok && ipnet.IP.Equal(target) {
				return ifi.Name
			}
		}
	}
	return ""
}

func isUsableAdvertise(ip string) bool {
	if !isPrivateLAN(ip) {
		return false
	}
	if name := ifaceName(ip); name != "" && isContainerBridge(name) {
		return false
	}
	return true
}

func lanTier(ip string) int {
	switch {
	case strings.HasPrefix(ip, "192.168."):
		return 0
	case strings.HasPrefix(ip, "10."):
		return 1
	default:
		return 2
	}
}

// bestLocalIP is the address other devices on the LAN can call back.
// Docker bridge addresses are skipped; those are not reachable from a phone.
func bestLocalIP() string {
	if ip := defaultRouteIPv4(); isUsableAdvertise(ip) {
		return ip
	}
	ifaces, err := net.Interfaces()
	if err != nil {
		return ""
	}
	best := ""
	bestTier := 99
	for _, ifi := range ifaces {
		if ifi.Flags&net.FlagUp == 0 || ifi.Flags&net.FlagLoopback != 0 || isContainerBridge(ifi.Name) {
			continue
		}
		addrs, err := ifi.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			ipnet, ok := addr.(*net.IPNet)
			if !ok {
				continue
			}
			ip := ipnet.IP.To4()
			if ip == nil || !isPrivateLAN(ip.String()) {
				continue
			}
			text := ip.String()
			tier := lanTier(text)
			if best == "" || tier < bestTier || (tier == bestTier && text < best) {
				best = text
				bestTier = tier
			}
		}
	}
	return best
}

func defaultRouteIPv4() string {
	data, err := os.ReadFile("/proc/net/route")
	if err != nil {
		return ""
	}
	lines := strings.Split(string(data), "\n")
	for _, line := range lines[1:] {
		fields := strings.Fields(line)
		if len(fields) < 4 || fields[1] != "00000000" {
			continue
		}
		flags, err := strconv.ParseInt(fields[3], 16, 64)
		if err != nil || flags&0x2 == 0 {
			continue
		}
		if ip := ipv4OnIface(fields[0]); ip != "" {
			return ip
		}
	}
	return ""
}

func ipv4OnIface(name string) string {
	ifi, err := net.InterfaceByName(name)
	if err != nil || isContainerBridge(ifi.Name) {
		return ""
	}
	addrs, err := ifi.Addrs()
	if err != nil {
		return ""
	}
	best := ""
	bestTier := 99
	for _, addr := range addrs {
		ipnet, ok := addr.(*net.IPNet)
		if !ok {
			continue
		}
		ip := ipnet.IP.To4()
		if ip == nil || !isPrivateLAN(ip.String()) {
			continue
		}
		text := ip.String()
		tier := lanTier(text)
		if best == "" || tier < bestTier {
			best = text
			bestTier = tier
		}
	}
	return best
}

func remoteIPv4(remote string) string {
	host := strings.TrimSpace(remote)
	if h, _, err := net.SplitHostPort(host); err == nil {
		host = h
	}
	host = strings.TrimPrefix(host, "::ffff:")
	ip := net.ParseIP(host).To4()
	if ip == nil || ip.IsLoopback() {
		return ""
	}
	return ip.String()
}
