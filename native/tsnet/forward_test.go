package tsnetbridge

import (
	"net"
	"testing"
	"time"
)

type remoteConn struct {
	net.Conn
	remote net.Addr
}

func (c remoteConn) RemoteAddr() net.Addr { return c.remote }

type queuedListener struct {
	conns  chan net.Conn
	closed chan struct{}
}

func (l *queuedListener) Accept() (net.Conn, error) {
	select {
	case c := <-l.conns:
		return c, nil
	case <-l.closed:
		return nil, net.ErrClosed
	}
}
func (l *queuedListener) Close() error { close(l.closed); return nil }
func (l *queuedListener) Addr() net.Addr {
	return &net.TCPAddr{IP: net.IPv4(100, 64, 0, 1), Port: 8080}
}

func serveForward(t *testing.T) (*queuedListener, net.Conn) {
	t.Helper()
	ln := &queuedListener{conns: make(chan net.Conn, 4), closed: make(chan struct{})}
	t.Cleanup(func() { _ = ln.Close() })
	go acceptToLocal(t.Context(), ln)
	client, inbound := tcpPair(t)
	ln.conns <- remoteConn{Conn: inbound, remote: &net.TCPAddr{IP: net.ParseIP("100.64.0.7"), Port: 41000}}
	return ln, client
}

func TestTailnetConnectionsAreSplicedToTheTLSFront(t *testing.T) {
	front, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer front.Close()
	SetForwardPort(front.Addr().(*net.TCPAddr).Port)
	t.Cleanup(func() { SetForwardPort(0) })

	got := make(chan string, 1)
	go func() {
		c, err := front.Accept()
		if err != nil {
			return
		}
		buf := make([]byte, 5)
		_, _ = c.Read(buf)
		got <- string(buf)
	}()

	_, client := serveForward(t)
	_, _ = client.Write([]byte("hello"))
	select {
	case v := <-got:
		if v != "hello" {
			t.Fatalf("got %q", v)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("nothing reached the TLS front")
	}
}

func TestNothingIsForwardedUntilTheTLSFrontIsKnown(t *testing.T) {
	SetForwardPort(0)
	_, client := serveForward(t)
	_ = client.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, err := client.Read(make([]byte, 1)); err == nil {
		t.Fatal("expected the connection to be closed")
	}
}

func TestAnInvalidForwardPortMeansNotReady(t *testing.T) {
	SetForwardPort(70000)
	if forwardPort.Load() != 0 {
		t.Fatalf("got %d", forwardPort.Load())
	}
}
