package com.fileapex.platform

actual fun firewallInboundStatus(): FirewallInboundStatus = FirewallInboundStatus.NotApplicable

actual fun allowFirewallInbound(): Boolean = false
