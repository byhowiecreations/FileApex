package main

/*
#include <stdlib.h>
#include <string.h>
*/
import "C"
import (
	"unsafe"

	"fileapex.dev/tsnetbridge"
)

//export fileapex_tsnet_start
func fileapex_tsnet_start(authKey, dir, hostname *C.char, sharePort C.int, outIP *C.char, outIPLen C.int, outErr *C.char, outErrLen C.int) C.int {
	ip, err := tsnetbridge.Start(C.GoString(authKey), C.GoString(dir), C.GoString(hostname), int(sharePort))
	writeCString(outIP, outIPLen, ip)
	if err != nil {
		writeCString(outErr, outErrLen, err.Error())
		return 0
	}
	writeCString(outErr, outErrLen, "")
	return 1
}

//export fileapex_tsnet_stop
func fileapex_tsnet_stop() {
	tsnetbridge.Stop()
}

//export fileapex_tsnet_logout
func fileapex_tsnet_logout(outErr *C.char, outErrLen C.int) C.int {
	if err := tsnetbridge.Logout(); err != nil {
		writeCString(outErr, outErrLen, err.Error())
		return 0
	}
	writeCString(outErr, outErrLen, "")
	return 1
}

//export fileapex_tsnet_loopback
func fileapex_tsnet_loopback(host *C.char, port C.int) C.int {
	local, err := tsnetbridge.Loopback(C.GoString(host), int(port))
	if err != nil {
		return 0
	}
	return C.int(local)
}

//export fileapex_tsnet_session_token
func fileapex_tsnet_session_token(out *C.char, outLen C.int) C.int {
	token := tsnetbridge.SessionToken()
	writeCString(out, outLen, token)
	if token == "" {
		return 0
	}
	return 1
}

//export fileapex_tsnet_backend_state
func fileapex_tsnet_backend_state(out *C.char, outLen C.int) {
	writeCString(out, outLen, tsnetbridge.BackendState())
}

//export fileapex_tsnet_auth_url
func fileapex_tsnet_auth_url(out *C.char, outLen C.int) {
	writeCString(out, outLen, tsnetbridge.AuthURL())
}

//export fileapex_tsnet_self_ipv4
func fileapex_tsnet_self_ipv4(out *C.char, outLen C.int) {
	writeCString(out, outLen, tsnetbridge.SelfIPv4())
}

//export fileapex_tsnet_key_expired
func fileapex_tsnet_key_expired() C.int {
	if tsnetbridge.KeyExpired() {
		return 1
	}
	return 0
}

//export fileapex_tsnet_clear_state
func fileapex_tsnet_clear_state(dir *C.char) C.int {
	if err := tsnetbridge.ClearState(C.GoString(dir)); err != nil {
		return 0
	}
	return 1
}

//export fileapex_tsnet_active_conns
func fileapex_tsnet_active_conns() C.int {
	return C.int(tsnetbridge.ActiveConnections())
}

//export fileapex_tsnet_peers
func fileapex_tsnet_peers(out *C.char, outLen C.int) C.int {
	payload, err := tsnetbridge.Peers()
	if err != nil || int(outLen) <= len(payload)+1 {
		writeCString(out, outLen, "")
		return 0
	}
	writeCString(out, outLen, payload)
	return 1
}

func writeCString(dst *C.char, n C.int, value string) {
	if dst == nil || n <= 0 {
		return
	}
	limit := int(n) - 1
	buf := []byte(value)
	if len(buf) > limit {
		buf = buf[:limit]
	}
	if len(buf) > 0 {
		C.memcpy(unsafe.Pointer(dst), unsafe.Pointer(&buf[0]), C.size_t(len(buf)))
	}
	*(*C.char)(unsafe.Add(unsafe.Pointer(dst), len(buf))) = 0
}

func main() {}
