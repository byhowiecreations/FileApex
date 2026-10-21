package tsnetbridge

import (
	"io"
	"net"
	"testing"
	"time"
)

func tcpPair(t *testing.T) (net.Conn, net.Conn) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	accepted := make(chan net.Conn, 1)
	go func() {
		c, _ := ln.Accept()
		accepted <- c
	}()
	client, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	return client, <-accepted
}

// A client that finishes sending and half-closes must still receive the whole reply.
func TestSpliceDeliversTheReplyAfterTheRequestSideHalfCloses(t *testing.T) {
	client, inbound := tcpPair(t)
	upstream, server := tcpPair(t)
	go splice(inbound, upstream)

	reply := make([]byte, 1<<20)
	go func() {
		defer server.Close()
		_, _ = io.Copy(io.Discard, server)
		time.Sleep(100 * time.Millisecond)
		_, _ = server.Write(reply)
	}()

	if _, err := client.Write([]byte("request")); err != nil {
		t.Fatal(err)
	}
	if err := client.(*net.TCPConn).CloseWrite(); err != nil {
		t.Fatal(err)
	}
	_ = client.SetReadDeadline(time.Now().Add(5 * time.Second))
	got, err := io.ReadAll(client)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != len(reply) {
		t.Fatalf("got %d of %d reply bytes", len(got), len(reply))
	}
}
