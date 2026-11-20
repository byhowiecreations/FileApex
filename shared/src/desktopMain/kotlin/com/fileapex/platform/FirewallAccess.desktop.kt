package com.fileapex.platform

actual fun firewallInboundStatus(): FirewallInboundStatus = WindowsFirewall.system(timeoutSeconds = STATUS_TIMEOUT_SECONDS).status()

actual fun allowFirewallInbound(): Boolean = WindowsFirewall.system(timeoutSeconds = ELEVATION_TIMEOUT_SECONDS).allow()

private const val STATUS_TIMEOUT_SECONDS = 45L
private const val ELEVATION_TIMEOUT_SECONDS = 180L
