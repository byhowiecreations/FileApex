package com.fileapex.platform

import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.domain.diagnostics.BatteryDiagnostics
import com.fileapex.domain.diagnostics.PeerDeviceDiagnostics
import com.fileapex.util.TimeUtils

expect fun collectPlatformDeviceDiagnostics(): PeerDeviceDiagnostics
expect fun collectFastBatteryDiagnostics(): BatteryDiagnostics

fun collectDeviceDiagnostics(): PeerDeviceDiagnostics {
    return collectPlatformDeviceDiagnostics()
        .copy(
            collectedAtEpochMs = TimeUtils.now(),
            platform = currentPlatformLabel()
        )
        .normalizedForTransport()
}

/** Minimal snapshot when live collection throws — avoids HTTP 500 on the diagnostics endpoint. */
fun collectDeviceDiagnosticsFallback(): PeerDeviceDiagnostics =
    PeerDeviceDiagnostics(
        collectedAtEpochMs = TimeUtils.now(),
        platform = currentPlatformLabel()
    )

/** Ensures kotlinx JSON transport never fails on NaN refresh rates, etc. */
private fun PeerDeviceDiagnostics.normalizedForTransport(): PeerDeviceDiagnostics {
    val refresh = display.refreshRateHz
    val safeRefresh = refresh?.takeIf { it.isFinite() && it > 0f }
    return if (safeRefresh == refresh) {
        this
    } else {
        copy(display = display.copy(refreshRateHz = safeRefresh))
    }
}
