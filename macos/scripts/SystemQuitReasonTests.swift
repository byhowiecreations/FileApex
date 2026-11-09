// Run with macos/scripts/test_system_quit_reason.sh. A quit caused by shutdown, restart or logout must never be cancelled.
import Foundation
var failures = 0
func check(_ ok: Bool, _ name: String) {
    if !ok { failures += 1; print("FAIL: \(name)") }
}

func code(_ s: String) -> UInt32 { s.utf8.reduce(0) { ($0 << 8) | UInt32($1) } }

check(isSystemQuitReason(code("shut")), "shutdown is a system quit")
check(isSystemQuitReason(code("rest")), "restart is a system quit")
check(isSystemQuitReason(code("rlgo")), "logout is a system quit")
check(!isSystemQuitReason(0), "no reason is a user quit")
check(!isSystemQuitReason(code("quia")), "quit all is a user quit")
check(!isSystemQuitReason(nil), "a missing reason is a user quit")

if failures > 0 { print("\(failures) failed"); exit(1) }
print("system quit reason: all passed")
