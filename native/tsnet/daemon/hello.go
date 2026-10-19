package main

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"log"
	"net"
	"strings"
	"sync"
	"time"
)

// Signed wake hello, receive side only. A device that joins a LAN broadcasts one packet on UDP 8888: a header
// (magic, sender id, server port, nonce, time) and one entry per paired peer, each a 4 byte tag of the
// recipient id and the first 8 bytes of an HMAC of the header under the key that pair shares. The proof shows the
// sender holds the pair key; this node still fetches the sender's identity from the packet's source address
// before it changes the stored address. The layout matches shared/.../network/WakeHello.kt.
const (
	helloPort      = 8888
	helloMagic     = "FAH1"
	helloAckMagic  = "FAK1"
	helloInfo      = "FileApex-Hello-v1"
	helloMaxPacket = 512
	helloTagBytes  = 4
	helloMacBytes  = 8
	helloNonceLen  = 8
	helloMaxSkew   = 2 * time.Minute
	helloMinGap    = 10 * time.Second
	helloNonceKeep = 5 * time.Minute
)

type helloEntry struct {
	tag [helloTagBytes]byte
	mac [helloMacBytes]byte
}

type helloPacket struct {
	ack      bool
	senderID string
	port     int
	nonce    [helloNonceLen]byte
	sentAt   int64
	signed   []byte
	entries  []helloEntry
}

func helloTag(deviceID string) [helloTagBytes]byte {
	sum := sha256.Sum256([]byte(deviceID))
	var tag [helloTagBytes]byte
	copy(tag[:], sum[:helloTagBytes])
	return tag
}

func parseHello(b []byte) (helloPacket, bool) {
	var p helloPacket
	if len(b) > helloMaxPacket || len(b) <= len(helloMagic) {
		return p, false
	}
	switch string(b[:len(helloMagic)]) {
	case helloMagic:
	case helloAckMagic:
		p.ack = true
	default:
		return p, false
	}
	at := len(helloMagic)
	idLen := int(b[at])
	at++
	if idLen == 0 || len(b) < at+idLen+2+helloNonceLen+8+1 {
		return p, false
	}
	p.senderID = string(b[at : at+idLen])
	at += idLen
	p.port = int(binary.BigEndian.Uint16(b[at:]))
	at += 2
	copy(p.nonce[:], b[at:at+helloNonceLen])
	at += helloNonceLen
	p.sentAt = int64(binary.BigEndian.Uint64(b[at:]))
	at += 8
	p.signed = b[:at]
	count := int(b[at])
	at++
	if count == 0 || len(b) != at+count*(helloTagBytes+helloMacBytes) {
		return p, false
	}
	for i := 0; i < count; i++ {
		var e helloEntry
		copy(e.tag[:], b[at:at+helloTagBytes])
		copy(e.mac[:], b[at+helloTagBytes:at+helloTagBytes+helloMacBytes])
		p.entries = append(p.entries, e)
		at += helloTagBytes + helloMacBytes
	}
	return p, true
}

// helloProof is the full HMAC-SHA256; packets carry its first helloMacBytes.
func (n *Node) helloProof(peer deviceRecord, data []byte) ([]byte, error) {
	if n.tls == nil {
		return nil, errors.New("no pair key")
	}
	key, err := pairKeyFor(n.tls.pairKey, peer.PublicKey, pairSalt(n.deviceID(), peer.DeviceID), helloInfo)
	if err != nil {
		return nil, err
	}
	mac := hmac.New(sha256.New, key)
	mac.Write(data)
	return mac.Sum(nil), nil
}

func (n *Node) verifyHello(p helloPacket, peer deviceRecord, now time.Time) bool {
	if peer.IsRemoved || peer.DeviceID != p.senderID || strings.TrimSpace(peer.PublicKey) == "" {
		return false
	}
	if p.port < 1 || p.port > 65535 {
		return false
	}
	skew := now.Sub(time.UnixMilli(p.sentAt))
	if skew > helloMaxSkew || skew < -helloMaxSkew {
		return false
	}
	mine := helloTag(n.deviceID())
	for _, e := range p.entries {
		if subtle.ConstantTimeCompare(e.tag[:], mine[:]) != 1 {
			continue
		}
		proof, err := n.helloProof(peer, p.signed)
		if err != nil {
			return false
		}
		return subtle.ConstantTimeCompare(e.mac[:], proof[:helloMacBytes]) == 1
	}
	return false
}

type helloGate struct {
	mu     sync.Mutex
	seen   map[string]time.Time
	lastBy map[string]time.Time
}

func (g *helloGate) accept(kind, sender string, nonce [helloNonceLen]byte, now time.Time) bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.seen == nil {
		g.seen = map[string]time.Time{}
		g.lastBy = map[string]time.Time{}
	}
	for k, at := range g.seen {
		if now.Sub(at) > helloNonceKeep {
			delete(g.seen, k)
		}
	}
	key := kind + ":" + sender + ":" + hex.EncodeToString(nonce[:])
	if _, dup := g.seen[key]; dup {
		return false
	}
	gapKey := kind + ":" + sender
	if last, ok := g.lastBy[gapKey]; ok && now.Sub(last) < helloMinGap {
		return false
	}
	g.seen[key] = now
	g.lastBy[gapKey] = now
	return true
}

func (n *Node) serveHello() {
	if n.tls == nil {
		return
	}
	lc := net.ListenConfig{Control: reuseAddr}
	pc, err := lc.ListenPacket(context.Background(), "udp4", net.JoinHostPort("0.0.0.0", "8888"))
	if err != nil {
		log.Printf("Wake hello not listening on UDP %d: %v", helloPort, err)
		return
	}
	conn, ok := pc.(*net.UDPConn)
	if !ok {
		pc.Close()
		return
	}
	n.mu.Lock()
	n.helloConn = conn
	n.mu.Unlock()
	log.Printf("Wake hello listening on UDP %d.", helloPort)
	go func() {
		buf := make([]byte, helloMaxPacket+1)
		var gate helloGate
		for {
			size, from, err := conn.ReadFromUDP(buf)
			if err != nil {
				if errors.Is(err, net.ErrClosed) {
					return
				}
				continue
			}
			if size > helloMaxPacket {
				continue
			}
			packet, ok := parseHello(append([]byte(nil), buf[:size]...))
			if !ok {
				continue // the plain wake string and anything else is not a hello
			}
			go n.acceptHello(packet, from, &gate, conn)
		}
	}()
}

func (n *Node) acceptHello(p helloPacket, from *net.UDPAddr, gate *helloGate, conn *net.UDPConn) {
	if p.senderID == n.deviceID() || from == nil || !isPrivateLAN(from.IP.String()) {
		return
	}
	peer, known := n.peerRecord(p.senderID)
	now := time.Now()
	if !known || !n.verifyHello(p, peer, now) {
		return
	}
	kind := "hello"
	if p.ack {
		kind = "ack"
	}
	if !gate.accept(kind, p.senderID, p.nonce, now) {
		return
	}
	host := from.IP.String()
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	state, err := n.fetchIdentity(ctx, host, p.port)
	if err != nil || strings.TrimSpace(state.DeviceID) != p.senderID {
		return
	}
	if peer.LastKnownIP != host || peer.Port != p.port {
		peer.LastKnownIP = host
		peer.Port = p.port
		n.upsertPeer(peer)
		log.Printf("%s is back on the LAN at %s.", n.peerLabel(peer.DeviceID), host)
	}
	if !p.ack {
		n.sendHelloAck(conn, peer, from.IP)
	}
}

func (n *Node) sendHelloAck(conn *net.UDPConn, peer deviceRecord, to net.IP) {
	id := n.deviceID()
	n.mu.Lock()
	port := n.port
	n.mu.Unlock()
	var nonce [helloNonceLen]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		return
	}
	header := []byte(helloAckMagic)
	header = append(header, byte(len(id)))
	header = append(header, id...)
	header = append(header, byte(port>>8), byte(port))
	header = append(header, nonce[:]...)
	var ts [8]byte
	binary.BigEndian.PutUint64(ts[:], uint64(time.Now().UnixMilli()))
	header = append(header, ts[:]...)
	proof, err := n.helloProof(peer, header)
	if err != nil {
		return
	}
	tag := helloTag(peer.DeviceID)
	packet := append(header, 1)
	packet = append(packet, tag[:]...)
	packet = append(packet, proof[:helloMacBytes]...)
	_, _ = conn.WriteToUDP(packet, &net.UDPAddr{IP: to, Port: helloPort})
}
