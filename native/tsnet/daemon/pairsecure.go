package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// securePair is the joiner half of pairing by comparison, over the host's TLS port. This node has no screen: it
// prints the number, and the host's user compares it with the host's screen and accepts there; the last request waits
// for that answer. It returns the host's TLS key, which every request of the pairing had to present unchanged.
func (n *Node) securePair(ctx context.Context, beacon pairingBeacon, hostID, hostPin string, tlsPort int, pin string, self deviceRecord) (string, error) {
	if tlsPort < 1 || tlsPort > 65535 {
		return "", errors.New("the other device offers no TLS port, so it cannot pair securely; update it")
	}
	if n.tls == nil {
		return "", errors.New("this node has no TLS identity")
	}
	joinerPin := n.tlsPinValue()
	nonceBytes := make([]byte, 16)
	if _, err := rand.Read(nonceBytes); err != nil {
		return "", err
	}
	nonceB := hex.EncodeToString(nonceBytes)

	observed := ""
	client := &http.Client{Transport: &http.Transport{
		DisableKeepAlives: true,
		TLSClientConfig: &tls.Config{
			Certificates:       []tls.Certificate{n.tls.identity.cert},
			InsecureSkipVerify: true, // identity is the key below, not a name or a CA
			MinVersion:         tls.VersionTLS12,
			VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
				if len(raw) == 0 {
					return errors.New("no server certificate")
				}
				leaf, err := x509.ParseCertificate(raw[0])
				if err != nil {
					return err
				}
				key, err := leafPin(leaf)
				if err != nil {
					return err
				}
				if observed != "" && observed != key {
					return errors.New("host key changed during pairing")
				}
				if hostPin != "" && hostPin != key {
					return errors.New("host key does not match what it announced")
				}
				observed = key
				return nil
			},
		},
	}}
	post := func(path string, value any, timeout time.Duration) (int, []byte, error) {
		payload, err := json.Marshal(value)
		if err != nil {
			return 0, nil, err
		}
		target := "https://" + net.JoinHostPort(beacon.IPAddress, strconv.Itoa(tlsPort)) + path
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, target, bytes.NewReader(payload))
		if err != nil {
			return 0, nil, err
		}
		req.Header.Set("Content-Type", "application/json")
		body, status, err := n.send(client, req, timeout)
		return status, body, err
	}

	status, body, err := post("/api/v1/pairing/secure/offer", map[string]any{
		"commit": pairCommit(nonceB, joinerPin, self.DeviceID), "scannerDevice": self, "pin": strings.TrimSpace(pin),
	}, 15*time.Second)
	if err != nil {
		return "", err
	}
	if status != http.StatusOK {
		return "", pairingRefusal(status, body)
	}
	var offer struct {
		SessionID string `json:"sessionId"`
		NonceA    string `json:"nonceA"`
	}
	if err := json.Unmarshal(body, &offer); err != nil || offer.SessionID == "" || offer.NonceA == "" {
		return "", errors.New("the other device sent a reply this node does not understand")
	}
	status, body, err = post("/api/v1/pairing/secure/reveal", map[string]any{"sessionId": offer.SessionID, "nonceB": nonceB}, 15*time.Second)
	if err != nil {
		return "", err
	}
	if status != http.StatusOK {
		return "", pairingRefusal(status, body)
	}
	code := pairSasCode(observed, joinerPin, hostID, self.DeviceID, offer.NonceA, nonceB)
	log.Printf("Pairing number %s. It must be the same on %s's screen. Accept it there, or decline if it differs.", code, beacon.DeviceName)
	// This node cannot look at a screen: the host's user is the one who compares, and the host decides.
	status, body, err = post("/api/v1/pairing/secure/decide", map[string]any{"sessionId": offer.SessionID, "approved": true}, 105*time.Second)
	if err != nil {
		return "", err
	}
	if status != http.StatusCreated {
		return "", pairingRefusal(status, body)
	}
	return observed, nil
}

func pairingRefusal(status int, body []byte) error {
	text := strings.ToLower(string(body))
	switch {
	case strings.Contains(text, "pairing_declined"):
		return errors.New("the other device declined the pairing, or did not answer in time")
	case strings.Contains(text, "pairing_busy"):
		return errors.New("the other device is already pairing with someone")
	case strings.Contains(text, "pin_required"):
		return errors.New("the other device rejected the PIN")
	case strings.Contains(text, "pairing_code"):
		return errors.New("the other device is not showing its pairing screen, or the pairing expired")
	}
	if status == http.StatusTooManyRequests {
		return errors.New("too many attempts; wait a minute and try again")
	}
	return fmt.Errorf("pairing refused (HTTP %d: %s)", status, strings.TrimSpace(string(body)))
}

