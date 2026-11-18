package com.fileapex.domain.notifications

import com.fileapex.di.FileApexServices
import com.fileapex.domain.link.LinkRecord
import com.fileapex.domain.link.PairLink
import com.fileapex.util.TimeUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.fileapex.util.cancellableCatching

/**
 * Phone side of the notification companion handshake. A paired computer says over the health poll whether
 * this phone is the one it is showing; the phone sends notifications only while that grant is live.
 */
@Serializable
internal data class CompanionGrantRecord(
    val grantorDeviceId: String,
    val active: Boolean,
    val expiresAtEpochMs: Long,
    /** When the computer's user chose this phone, 0 when it was only the computer's guess. */
    val chosenAtEpochMs: Long = 0L
)

object NotificationCompanionGrant {
    /** A grant covers this long after the last poll that carried it, so a vanished computer stops the phone. */
    const val LEASE_MS = 10 * 60 * 1000L

    private const val RENEW_WRITE_GAP_MS = 60 * 1000L

    private val json = Json { ignoreUnknownKeys = true }

    /** Records the computer's latest answer. Call only for a request that arrived over TLS from a paired computer. */
    fun record(computerId: String, signal: PairLink.Signal, now: Long = TimeUtils.now()) {
        val settings = FileApexServices.settings
        recorded(settings.pairLink.grant.value, computerId.trim(), signal, now)?.let {
            settings.pairLink.setGrant(it)
        }
    }

    /** The JSON to store after [computerId] sent [signal], or null when nothing needs writing. */
    internal fun recorded(stored: String, computerId: String, signal: PairLink.Signal, now: Long): String? {
        if (computerId.isEmpty()) return null
        val existing = currentLink(stored, Long.MIN_VALUE)
        val merged = PairLink.merge(existing, computerId, signal, now) ?: return null
        if (merged == existing) return null
        // Every request from the computer renews the grant; only write when it changed or is about to age.
        if (existing != null && existing.peerId == merged.peerId && existing.active == merged.active &&
            existing.chosenAtEpochMs == merged.chosenAtEpochMs &&
            (!merged.active || existing.expiresAtEpochMs - now > LEASE_MS - RENEW_WRITE_GAP_MS)
        ) return null
        return encode(CompanionGrantRecord(merged.peerId, merged.active, merged.expiresAtEpochMs, merged.chosenAtEpochMs))
    }

    /** The stored record as a link record when it is still live (pass the time to test against). */
    internal fun currentLink(stored: String, now: Long): LinkRecord? =
        decode(stored)?.takeIf { it.active || now < it.expiresAtEpochMs }
            ?.let { LinkRecord(it.grantorDeviceId, it.active, it.chosenAtEpochMs, it.expiresAtEpochMs) }

    fun currentLink(now: Long = TimeUtils.now()): LinkRecord? =
        currentLink(FileApexServices.settings.pairLink.grant.value, now)

    /** False only when [computerId] has granted before and the grant is now stood down or expired. */
    fun allows(computerId: String, now: Long = TimeUtils.now()): Boolean =
        allows(FileApexServices.settings.pairLink.grant.value, computerId, now)

    /** Strict: true only while [computerId] holds a live grant. Locating a phone needs this, not just the absence of a stand-down. */
    fun isActive(computerId: String, now: Long = TimeUtils.now()): Boolean =
        isActive(FileApexServices.settings.pairLink.grant.value, computerId, now)

    /** The computer whose live grant this phone holds, or null. */
    fun activeGrantor(now: Long = TimeUtils.now()): String? =
        decode(FileApexServices.settings.pairLink.grant.value)
            ?.takeIf { it.active && now < it.expiresAtEpochMs }?.grantorDeviceId

    internal fun isActive(stored: String, computerId: String, now: Long): Boolean {
        val record = decode(stored) ?: return false
        return record.grantorDeviceId == computerId.trim() && record.active && now < record.expiresAtEpochMs
    }

    internal fun allows(stored: String, computerId: String, now: Long): Boolean {
        // No record is the default (allowed); a record that cannot be read is not a reason to open anything up.
        val record = decode(stored) ?: return stored.isBlank()
        if (record.grantorDeviceId != computerId.trim()) return true
        return record.active && now < record.expiresAtEpochMs
    }

    internal fun encode(record: CompanionGrantRecord): String =
        json.encodeToString(CompanionGrantRecord.serializer(), record)

    internal fun decode(stored: String): CompanionGrantRecord? {
        if (stored.isBlank()) return null
        return cancellableCatching("NotificationCompanionGrant: json.decodeFromString") { json.decodeFromString(CompanionGrantRecord.serializer(), stored) }.getOrNull()
    }
}
