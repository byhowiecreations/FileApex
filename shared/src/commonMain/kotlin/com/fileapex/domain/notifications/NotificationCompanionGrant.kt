package com.fileapex.domain.notifications

import com.fileapex.di.FileApexServices
import com.fileapex.util.TimeUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Phone side of the notification companion handshake. A paired computer says over the health poll whether
 * this phone is the one it is showing; the phone sends notifications only while that grant is live.
 */
@Serializable
internal data class CompanionGrantRecord(
    val grantorDeviceId: String,
    val active: Boolean,
    val expiresAtEpochMs: Long
)

object NotificationCompanionGrant {
    /** A grant covers this long after the last poll that carried it, so a vanished computer stops the phone. */
    const val LEASE_MS = 10 * 60 * 1000L

    private const val RENEW_WRITE_GAP_MS = 60 * 1000L

    private val json = Json { ignoreUnknownKeys = true }

    /** Records the computer's latest answer. Call only for a request that arrived over TLS from a paired computer. */
    fun record(computerId: String, active: Boolean, now: Long = TimeUtils.now()) {
        val id = computerId.trim()
        if (id.isEmpty()) return
        val settings = FileApexServices.settings
        // Every request from the computer renews the grant; only write when it changed or is about to age.
        decode(settings.notificationCompanionGrant.value)?.let { existing ->
            val unchanged = existing.grantorDeviceId == id && existing.active == active
            if (unchanged && (!active || existing.expiresAtEpochMs - now > LEASE_MS - RENEW_WRITE_GAP_MS)) return
        }
        settings.setNotificationCompanionGrant(
            encode(CompanionGrantRecord(id, active, if (active) now + LEASE_MS else 0L))
        )
    }

    /** False only when [computerId] has granted before and the grant is now stood down or expired. */
    fun allows(computerId: String, now: Long = TimeUtils.now()): Boolean =
        allows(FileApexServices.settings.notificationCompanionGrant.value, computerId, now)

    /** Strict: true only while [computerId] holds a live grant. Locating a phone needs this, not just the absence of a stand-down. */
    fun isActive(computerId: String, now: Long = TimeUtils.now()): Boolean =
        isActive(FileApexServices.settings.notificationCompanionGrant.value, computerId, now)

    /** The computer whose live grant this phone holds, or null. */
    fun activeGrantor(now: Long = TimeUtils.now()): String? =
        decode(FileApexServices.settings.notificationCompanionGrant.value)
            ?.takeIf { it.active && now < it.expiresAtEpochMs }?.grantorDeviceId

    internal fun isActive(stored: String, computerId: String, now: Long): Boolean {
        val record = decode(stored) ?: return false
        return record.grantorDeviceId == computerId.trim() && record.active && now < record.expiresAtEpochMs
    }

    internal fun allows(stored: String, computerId: String, now: Long): Boolean {
        val record = decode(stored) ?: return true
        if (record.grantorDeviceId != computerId.trim()) return true
        return record.active && now < record.expiresAtEpochMs
    }

    internal fun encode(record: CompanionGrantRecord): String =
        json.encodeToString(CompanionGrantRecord.serializer(), record)

    internal fun decode(stored: String): CompanionGrantRecord? {
        if (stored.isBlank()) return null
        return runCatching { json.decodeFromString(CompanionGrantRecord.serializer(), stored) }.getOrNull()
    }
}
