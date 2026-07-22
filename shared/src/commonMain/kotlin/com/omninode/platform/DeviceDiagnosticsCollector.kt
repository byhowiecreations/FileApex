package com.omninode.platform

import com.omninode.cloud.currentPlatformLabel
import com.omninode.domain.diagnostics.PeerDeviceDiagnostics
import com.omninode.util.TimeUtils

expect fun collectPlatformDeviceDiagnostics(): PeerDeviceDiagnostics

fun collectDeviceDiagnostics(): PeerDeviceDiagnostics {
    return collectPlatformDeviceDiagnostics().copy(
        collectedAtEpochMs = TimeUtils.now(),
        platform = currentPlatformLabel()
    )
}
