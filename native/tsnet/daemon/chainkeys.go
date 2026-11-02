package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"encoding/pem"
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

const signingKeyFile = "chain_signing.key"

// loadOrCreateSigningKey is this node's key for the membership chain. It is not the TLS key: the TLS key proves
// which device is on a connection, this one signs what the device says about the cluster.
func loadOrCreateSigningKey(dir string) (*ecdsa.PrivateKey, error) {
	path := filepath.Join(dir, signingKeyFile)
	body, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return renewSigningKey(dir)
	}
	if err != nil {
		return nil, err
	}
	block, _ := pem.Decode(body)
	if block == nil {
		return nil, fmt.Errorf("%s is not a PEM file", signingKeyFile)
	}
	key, err := x509.ParseECPrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("%s: %w", signingKeyFile, err)
	}
	if key.Curve != elliptic.P256() {
		return nil, fmt.Errorf("%s is not a P-256 key", signingKeyFile)
	}
	return key, nil
}

// renewSigningKey replaces the key, for a node that let go of its cluster or was removed under the old key: the chain
// never takes a removed key back.
func renewSigningKey(dir string) (*ecdsa.PrivateKey, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	der, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return nil, err
	}
	pemBytes := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: der})
	if err := writeSecretFile(filepath.Join(dir, signingKeyFile), pemBytes); err != nil {
		return nil, err
	}
	return key, nil
}
