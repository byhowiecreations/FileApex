package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"strconv"
	"strings"
	"syscall"
	"time"
)

const (
	beaconPort      = 8891
	multicastGroup  = "239.255.0.89"
	beaconPrefix    = "FA_PAIR:"
	beaconReadSlice = 2 * time.Second
)

type pairingBeacon struct {
	DeviceName  string `json:"deviceName"`
	IPAddress   string `json:"ipAddress"`
	Port        int    `json:"port"`
	PairingCode string `json:"pairingCode"`
	Timestamp   int64  `json:"timestamp"`
	DeviceID    string `json:"deviceId"`
	PinRequired bool   `json:"pinRequired"`
}

func parseBeacon(raw string) (pairingBeacon, bool) {
	text := strings.TrimSpace(strings.TrimPrefix(raw, "\uFEFF"))
	switch {
	case strings.HasPrefix(text, beaconPrefix):
		text = strings.TrimSpace(strings.TrimPrefix(text, beaconPrefix))
	case strings.HasPrefix(text, "{"):
	default:
		return pairingBeacon{}, false
	}
	var beacon pairingBeacon
	if err := json.Unmarshal([]byte(text), &beacon); err != nil {
		return pairingBeacon{}, false
	}
	beacon.DeviceID = strings.TrimSpace(beacon.DeviceID)
	beacon.DeviceName = strings.TrimSpace(beacon.DeviceName)
	beacon.IPAddress = strings.TrimSpace(beacon.IPAddress)
	beacon.PairingCode = digitsOnly(beacon.PairingCode)
	if beacon.DeviceID == "" || beacon.DeviceName == "" || beacon.IPAddress == "" {
		return pairingBeacon{}, false
	}
	if beacon.Port < 1 || beacon.Port > 65535 || len(beacon.PairingCode) != 6 {
		return pairingBeacon{}, false
	}
	return beacon, true
}

func digitsOnly(value string) string {
	var b strings.Builder
	for _, r := range value {
		if r >= '0' && r <= '9' {
			b.WriteRune(r)
		}
	}
	return b.String()
}

func acceptableBeaconIP(ip string, allowLoopback bool) bool {
	if isPrivateLAN(ip) {
		return true
	}
	parsed := net.ParseIP(strings.TrimSpace(ip))
	return allowLoopback && parsed != nil && parsed.IsLoopback()
}

// waitForBeacon blocks until another device broadcasts a pairing code.
// This process never generates a code and never sends a beacon.
func waitForBeacon(ctx context.Context, selfID string, allowLoopback bool) (pairingBeacon, error) {
	conn, err := listenBeaconSocket(ctx)
	if err != nil {
		return pairingBeacon{}, err
	}
	defer conn.Close()
	go func() {
		<-ctx.Done()
		conn.Close()
	}()

	buf := make([]byte, 2048)
	ignored := map[string]struct{}{}
	lastLog := time.Now()
	log.Printf("Waiting for a pairing broadcast on UDP %d.", beaconPort)
	for {
		if err := ctx.Err(); err != nil {
			return pairingBeacon{}, err
		}
		_ = conn.SetReadDeadline(time.Now().Add(beaconReadSlice))
		n, _, err := conn.ReadFrom(buf)
		if err != nil {
			if ctx.Err() != nil {
				return pairingBeacon{}, ctx.Err()
			}
			if ne, ok := err.(net.Error); ok && ne.Timeout() {
				if time.Since(lastLog) >= 15*time.Second {
					log.Printf("Still waiting. On another device, open FileApex and leave the pairing screen up.")
					lastLog = time.Now()
				}
				continue
			}
			return pairingBeacon{}, err
		}
		beacon, ok := parseBeacon(string(buf[:n]))
		if !ok || beacon.DeviceID == selfID {
			continue
		}
		if !acceptableBeaconIP(beacon.IPAddress, allowLoopback) {
			if _, seen := ignored[beacon.IPAddress]; !seen {
				ignored[beacon.IPAddress] = struct{}{}
				log.Printf("Ignoring pairing broadcast from %s: %s is not a private LAN address.", beacon.DeviceName, beacon.IPAddress)
			}
			continue
		}
		return beacon, nil
	}
}

func listenBeaconSocket(ctx context.Context) (*net.UDPConn, error) {
	lc := net.ListenConfig{Control: reuseAddr}
	pc, err := lc.ListenPacket(ctx, "udp4", net.JoinHostPort("0.0.0.0", strconv.Itoa(beaconPort)))
	if err != nil {
		return nil, fmt.Errorf("listen on UDP %d: %w (another FileApex app on this machine may already be using it)", beaconPort, err)
	}
	conn, ok := pc.(*net.UDPConn)
	if !ok {
		pc.Close()
		return nil, fmt.Errorf("unexpected UDP socket %T", pc)
	}
	joined := joinMulticast(conn)
	if joined == 0 {
		log.Printf("Could not join multicast %s; still listening for LAN broadcasts on UDP %d.", multicastGroup, beaconPort)
	} else {
		log.Printf("Joined multicast %s on %d interface(s).", multicastGroup, joined)
	}
	return conn, nil
}

func reuseAddr(network, address string, c syscall.RawConn) error {
	var sockErr error
	err := c.Control(func(fd uintptr) {
		sockErr = syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_REUSEADDR, 1)
	})
	if err != nil {
		return err
	}
	return sockErr
}

func joinMulticast(conn *net.UDPConn) int {
	group := net.ParseIP(multicastGroup).To4()
	if group == nil {
		return 0
	}
	raw, err := conn.SyscallConn()
	if err != nil {
		return 0
	}
	ifaces, err := net.Interfaces()
	if err != nil {
		return 0
	}
	joined := 0
	for _, ifi := range ifaces {
		if ifi.Flags&net.FlagUp == 0 || ifi.Flags&net.FlagLoopback != 0 || ifi.Flags&net.FlagMulticast == 0 {
			continue
		}
		ip4 := firstIPv4(ifi)
		if ip4 == nil {
			continue
		}
		if err := addMembership(raw, group, ip4); err != nil {
			log.Printf("Multicast join skipped on %s: %v", ifi.Name, err)
			continue
		}
		joined++
	}
	if joined == 0 && addMembership(raw, group, net.IPv4zero) == nil {
		joined++
	}
	return joined
}

func firstIPv4(ifi net.Interface) net.IP {
	addrs, err := ifi.Addrs()
	if err != nil {
		return nil
	}
	for _, addr := range addrs {
		ipnet, ok := addr.(*net.IPNet)
		if !ok {
			continue
		}
		if ip4 := ipnet.IP.To4(); ip4 != nil {
			return ip4
		}
	}
	return nil
}

func addMembership(raw syscall.RawConn, group, iface net.IP) error {
	mreq := &syscall.IPMreq{}
	copy(mreq.Multiaddr[:], group.To4())
	copy(mreq.Interface[:], iface.To4())
	var setErr error
	if err := raw.Control(func(fd uintptr) {
		setErr = syscall.SetsockoptIPMreq(int(fd), syscall.IPPROTO_IP, syscall.IP_ADD_MEMBERSHIP, mreq)
	}); err != nil {
		return err
	}
	return setErr
}
