package com.fileapex.cloud.drive

import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.di.FileApexServices
import com.fileapex.platform.DriveRelayNotifier

/**
 * Single source of truth for whether this device may send/receive via Google Drive Relay.
 */
object DriveRelayPolicy {
    const val NOTES_ATTACHMENT_MAX_BYTES: Long = 5L * 1024L * 1024L
    const val PURGE_AFTER_MS: Long = 72L * 60L * 60L * 1000L
    /** Background log.md ETag poll — 15 minutes keeps Drive well under rate limits. */
    const val LEDGER_POLL_INTERVAL_MS: Long = 15L * 60L * 1000L

    fun isRelayEnabled(): Boolean {
        val settings = FileApexServices.settings
        if (!settings.googleAccountLinkEnabled.value) return false
        if (!GoogleDriveAuth.hasGrant()) return false
        if (settings.googleDriveRelayEnabled.value) return true
        // Desktop is never "on cellular" but still originates Drive + FCM to phones that are.
        return currentPlatformLabel() != "Android"
    }

    /**
     * Re-verify a stored Drive grant before an off-LAN send so a Mac on Wi-Fi does not
     * fall through to the LAN queue after the destination is already known to be on cellular.
     */
    suspend fun ensureReadyForSend(): Boolean {
        val settings = FileApexServices.settings
        if (currentPlatformLabel() != "Android" &&
            settings.googleAccountLinkEnabled.value &&
            GoogleDriveAuth.hasGrant()
        ) {
            if (!settings.googleDriveRelayEnabled.value) {
                settings.setGoogleDriveRelayEnabled(true)
            }
            return true
        }
        if (isRelayEnabled()) return true
        if (!settings.googleAccountLinkEnabled.value) {
            driveLog("relay send blocked — Google Account not linked")
            return false
        }
        if (GoogleDriveAuth.hasStoredAccess() && !GoogleDriveAuth.hasGrant()) {
            runCatching {
                GoogleDriveClient.verifyRelayAccess()
                GoogleDriveAuth.markAccessVerified()
                if (!settings.googleDriveRelayEnabled.value) {
                    settings.setGoogleDriveRelayEnabled(true)
                }
                DriveRelayNotifier.onDriveEnabledAndGranted()
            }.onFailure { error ->
                driveLogError("send-time Drive grant probe failed", error)
            }
        }
        val ready = isRelayEnabled()
        if (!ready) {
            driveLog(
                "relay send blocked — cellular=${settings.cellularEnabled.value} " +
                    "drive=${settings.googleDriveRelayEnabled.value} " +
                    "grant=${GoogleDriveAuth.hasGrant()} platform=${currentPlatformLabel()}"
            )
        }
        return ready
    }

    fun canSend(): Boolean = isRelayEnabled()

    fun canReceive(): Boolean = isRelayEnabled()

    fun needsSendPrompt(): Boolean = false

    fun needsReceivePrompt(): Boolean = false
}
