package com.fileapex.domain.link

import com.fileapex.di.FileApexServices
import com.fileapex.util.TimeUtils
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Computer side: the phone that last announced it is showing this computer, or that it left. */
object PhoneLinkClaim {
    private const val RENEW_WRITE_GAP_MS = 60 * 1000L
    private val json = Json { ignoreUnknownKeys = true }

    /** Call only for a request that arrived over TLS from a paired phone. */
    fun record(phoneId: String, signal: PairLink.Signal, now: Long = TimeUtils.now()) {
        val settings = FileApexServices.settings
        recorded(settings.pairLink.phoneClaim.value, phoneId.trim(), signal, now)?.let { settings.pairLink.setPhoneClaim(it) }
    }

    fun current(now: Long = TimeUtils.now()): LinkRecord? = current(FileApexServices.settings.pairLink.phoneClaim.value, now)

    /** The JSON to store after [phoneId] announced [signal], or null when nothing needs writing. */
    internal fun recorded(stored: String, phoneId: String, signal: PairLink.Signal, now: Long): String? {
        if (phoneId.isEmpty()) return null
        val existing = decode(stored)
        val merged = PairLink.merge(existing, phoneId, signal, now) ?: return null
        if (merged == existing) return null
        if (existing != null && merged.peerId == existing.peerId && merged.active == existing.active &&
            merged.chosenAtEpochMs == existing.chosenAtEpochMs && merged.active &&
            existing.expiresAtEpochMs - now > PairLink.LEASE_MS - RENEW_WRITE_GAP_MS
        ) return null
        return json.encodeToString(LinkRecord.serializer(), merged)
    }

    internal fun current(stored: String, now: Long): LinkRecord? =
        decode(stored)?.takeIf { it.active || now < it.expiresAtEpochMs }

    private fun decode(stored: String): LinkRecord? {
        if (stored.isBlank()) return null
        return try {
            json.decodeFromString(LinkRecord.serializer(), stored)
        } catch (error: SerializationException) {
            println("PhoneLinkClaim: stored claim could not be read, ignoring it")
            null
        }
    }
}
