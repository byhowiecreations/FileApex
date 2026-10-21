package com.fileapex.platform

import com.fileapex.di.FileApexServices
import com.fileapex.domain.diagnostics.AppDiagnostics
import com.fileapex.update.currentAppVersionCode
import com.fileapex.update.currentAppVersionName
import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.domain.diagnostics.BatteryDiagnostics
import com.fileapex.domain.diagnostics.PeerDeviceDiagnostics
import com.fileapex.util.TimeUtils

expect fun collectPlatformDeviceDiagnostics(): PeerDeviceDiagnostics

/** Only what a summary view shows (identity, battery, storage, memory, uptime); skips the slow probes. */
expect fun collectPlatformDeviceSummary(): PeerDeviceDiagnostics
expect fun collectFastBatteryDiagnostics(): BatteryDiagnostics

fun collectDeviceDiagnostics(): PeerDeviceDiagnostics {
    return collectPlatformDeviceDiagnostics()
        .copy(
            collectedAtEpochMs = TimeUtils.now(),
            platform = currentPlatformLabel(),
            app = collectAppDiagnostics()
        )
        .normalizedForTransport()
}

fun collectDeviceSummary(): PeerDeviceDiagnostics {
    return collectPlatformDeviceSummary()
        .copy(
            collectedAtEpochMs = TimeUtils.now(),
            platform = currentPlatformLabel(),
            app = collectAppDiagnostics()
        )
        .normalizedForTransport()
}

/** Minimal snapshot when live collection throws, avoids HTTP 500 on the diagnostics endpoint. */
fun collectDeviceDiagnosticsFallback(): PeerDeviceDiagnostics =
    PeerDeviceDiagnostics(
        collectedAtEpochMs = TimeUtils.now(),
        platform = currentPlatformLabel(),
        app = collectAppDiagnostics()
    )

/** This install's version and where it came from, reported with the rest of the device details. */
fun collectAppDiagnostics(): AppDiagnostics = AppDiagnostics(
    versionName = currentAppVersionName(),
    versionCode = currentAppVersionCode(),
    source = when {
        FileApexServices.isPlayStoreBuild -> "play"
        FileApexServices.isFdroidBuild -> "fdroid"
        else -> "github"
    }
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
