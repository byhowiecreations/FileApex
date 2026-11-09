import AppKit

private let reasonKeyword: AEKeyword = 0x7768793F // 'why?'
private let systemQuitReasons: Set<UInt32> = [
    0x73687574, // 'shut' shutdown
    0x72657374, // 'rest' restart
    0x726C676F  // 'rlgo' logout
]

func isSystemQuitReason(_ code: UInt32?) -> Bool {
    guard let code else { return false }
    return systemQuitReasons.contains(code)
}

/// True when the Quit event now being handled comes from shutdown, restart or logout.
/// The event is only valid while applicationShouldTerminate runs.
func currentQuitIsSystemSessionEnd() -> Bool {
    let event = NSAppleEventManager.shared().currentAppleEvent
    let code = event?.attributeDescriptor(forKeyword: reasonKeyword)?.enumCodeValue
    return isSystemQuitReason(code)
}
