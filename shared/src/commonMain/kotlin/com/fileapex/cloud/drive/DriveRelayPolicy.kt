package com.fileapex.cloud.drive

import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.data.settings.DriveRelayMaxMb
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.platform.DriveRelayNotifier
import kotlin.random.Random

object DriveRelayPolicy {
    const val NOTES_LAN_ATTACHMENT_MAX_BYTES: Long = 35L * 1024L * 1024L
    const val PURGE_AFTER_MS: Long = 72L * 60L * 60L * 1000L
    /** Background log.md ETag poll — 15 minutes keeps Drive well under rate limits. */
    const val LEDGER_POLL_INTERVAL_MS: Long = 15L * 60L * 1000L
    const val RECEIVE_RETRIES: Int = 2

    fun receiveRetryDelayMs(): Long = 3_000L + Random.nextLong(15_001L)

    fun relayMaxMb(): DriveRelayMaxMb = FileApexServices.settings.driveRelayMaxMb.value

    fun relayMaxBytes(): Long = relayMaxMb().bytes

    fun relayLimitLabel(): String = relayMaxMb().label

    fun lanAttachmentLimitLabel(): String = "35 MB"

    fun formatBytesLabel(bytes: Long): String {
        val mb = bytes / (1024L * 1024L)
        val remainder = bytes % (1024L * 1024L)
        return if (remainder == 0L) {
            "$mb MB"
        } else {
            val tenths = ((remainder * 10) / (1024L * 1024L)).coerceAtLeast(1L)
            "$mb.$tenths MB"
        }
    }

    fun payloadExceedsRelayLimit(sizeBytes: Long): Boolean = sizeBytes > relayMaxBytes()

    fun payloadExceedsRelayLimit(sizeBytesList: List<Long>): Boolean {
        if (sizeBytesList.isEmpty()) return false
        val sizes = sizeBytesList.map { it.coerceAtLeast(0L) }
        if (sizes.any { it > relayMaxBytes() }) return true
        return sizes.sum() > relayMaxBytes()
    }

    fun relayLimitExceededMessage(sizeBytes: Long): String =
        AppI18n.t("drive_relay_limit_file", formatBytesLabel(sizeBytes), relayLimitLabel())

    fun relayLimitExceededMessage(sizeBytesList: List<Long>): String {
        val sizes = sizeBytesList.map { it.coerceAtLeast(0L) }
        val total = sizes.sum()
        if (sizes.size <= 1) return relayLimitExceededMessage(total)
        return AppI18n.t("drive_relay_limit_group", formatBytesLabel(total), relayLimitLabel())
    }

    fun evaluateNotesAttachment(sizeBytes: Long): NotesAttachmentDecision {
        val relayReady = canSend()
        val overLan = sizeBytes > NOTES_LAN_ATTACHMENT_MAX_BYTES
        val overRelay = sizeBytes > relayMaxBytes()
        if (!overLan) return NotesAttachmentDecision.AllowLan
        if (relayReady && !overRelay) return NotesAttachmentDecision.AllowRelay
        if (overRelay) {
            return NotesAttachmentDecision.TooLargeForRelay(
                fileLabel = formatBytesLabel(sizeBytes),
                relayLimitLabel = relayLimitLabel()
            )
        }
        val settings = FileApexServices.settings
        return if (!settings.driveRelayOptInPromptShown.value) {
            NotesAttachmentDecision.OfferRelayOptIn(
                fileLabel = formatBytesLabel(sizeBytes),
                lanLimitLabel = lanAttachmentLimitLabel(),
                relayLimitLabel = relayLimitLabel()
            )
        } else {
            NotesAttachmentDecision.NeedsRelayEnabled(
                fileLabel = formatBytesLabel(sizeBytes),
                lanLimitLabel = lanAttachmentLimitLabel()
            )
        }
    }

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
            driveLog("relay send blocked - Google Account not linked")
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
                "relay send blocked - cellular=${settings.cellularEnabled.value} " +
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

sealed class NotesAttachmentDecision {
    data object AllowLan : NotesAttachmentDecision()
    data object AllowRelay : NotesAttachmentDecision()
    data class OfferRelayOptIn(
        val fileLabel: String,
        val lanLimitLabel: String,
        val relayLimitLabel: String
    ) : NotesAttachmentDecision()
    data class NeedsRelayEnabled(
        val fileLabel: String,
        val lanLimitLabel: String
    ) : NotesAttachmentDecision()
    data class TooLargeForRelay(
        val fileLabel: String,
        val relayLimitLabel: String
    ) : NotesAttachmentDecision()
}
