package com.fileapex.platform

enum class FirewallInboundStatus {
    /** Not Windows, or the app's own path is unknown: there is nothing to check. */
    NotApplicable,
    Allowed,
    /** An enabled inbound Block rule exists for this app, usually from dismissing Windows' first prompt. */
    Blocked,
    /** The firewall is on and holds no inbound Allow rule for this app. */
    NoRule,
    /** The check itself failed: no card, because the state is not known. */
    Unknown
}

/** Reads the Windows Firewall rules for this app. Starts a process, so call it off the UI thread. */
expect fun firewallInboundStatus(): FirewallInboundStatus

/** Asks Windows (one UAC prompt) to allow inbound connections for this app. True when the rule was added. Blocks until the prompt is answered. */
expect fun allowFirewallInbound(): Boolean
