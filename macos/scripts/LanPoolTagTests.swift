// Run with scripts/test_lan_pool_tag.sh. A parked socket may only serve a request that needs the same kind of connection.
import Foundation
var failures = 0
func check(_ ok: Bool, _ name: String) {
    if !ok { failures += 1; print("FAIL: \(name)") }
}

check(lanPoolTag(tlsPort: nil, pins: []) != lanPoolTag(tlsPort: 8081, pins: ["aa"]), "a plain socket is not a TLS socket")
check(lanPoolTag(tlsPort: 8081, pins: ["aa"]) == lanPoolTag(tlsPort: 8081, pins: ["aa"]), "the same route has the same tag")
check(lanPoolTag(tlsPort: 8081, pins: ["aa"]) != lanPoolTag(tlsPort: 8081, pins: ["bb"]), "a re-keyed peer gets a new tag")
check(lanPoolTag(tlsPort: 8081, pins: ["aa"]) != lanPoolTag(tlsPort: 8443, pins: ["aa"]), "a moved TLS port gets a new tag")
check(lanPoolTag(tlsPort: 8081, pins: ["aa", "bb"]) == lanPoolTag(tlsPort: 8081, pins: ["bb", "aa"]), "pin order does not matter")
check(lanPoolTag(tlsPort: nil, pins: ["aa"]) == lanPoolTag(tlsPort: nil, pins: []), "plain ignores pins")

if failures > 0 { print("\(failures) failed"); exit(1) }
print("lan pool tag: all passed")
