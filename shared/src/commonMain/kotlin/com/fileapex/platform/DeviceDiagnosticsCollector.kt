package com.fileapex.platform

import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.domain.diagnostics.PeerDeviceDiagnostics
import com.fileapex.util.TimeUtils

expect fun collectPlatformDeviceDiagnostics(): PeerDeviceDiagnostics

fun collectDeviceDiagnostics(): PeerDeviceDiagnostics {
    return collectPlatformDeviceDiagnostics().copy(
        collectedAtEpochMs = TimeUtils.now(),
        platform = currentPlatformLabel()
    )
}
