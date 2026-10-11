package com.fileapex.data.device

import com.fileapex.data.db.PairedDeviceEntity

/** How long an unverified introduction is kept before it is dropped. */
const val CANDIDATE_TTL_MS = 2L * 60L * 60L * 1000L

enum class CandidateOrigin {
    /** Sealed by a trusted peer with the pair key it shares with this device. */
    INTRODUCED,

    /** Carried by a peer that mutual TLS authenticated; older builds cannot seal introductions. */
    TRANSPORT,

    /** Read from this account's cloud registry. */
    CLOUD,

    /** Already in this device's own storage: demoted roster rows and legacy database recovery. */
    LOCAL
}

/**
 * A peer this device was told about but has not verified itself. It is never listed, never shown, never
 * handed to anyone else and carries no tombstone; it is promoted after a direct check or dropped at expiry.
 */
data class PeerCandidate(
    val device: PairedDeviceEntity,
    val introducedBy: String,
    val origin: CandidateOrigin,
    val introducedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val attempts: Int = 0,
    val lastAttemptEpochMs: Long = 0L
) {
    val deviceId: String get() = device.deviceId
}

interface PeerCandidateStore {
    suspend fun put(candidate: PeerCandidate)
    suspend fun get(deviceId: String): PeerCandidate?
    suspend fun all(): List<PeerCandidate>
    suspend fun remove(deviceId: String)
    suspend fun removeExpired(nowEpochMs: Long)
    suspend fun clear()
}

class InMemoryPeerCandidateStore : PeerCandidateStore {
    private val rows = LinkedHashMap<String, PeerCandidate>()

    override suspend fun put(candidate: PeerCandidate) {
        rows[candidate.deviceId] = candidate
    }

    override suspend fun get(deviceId: String): PeerCandidate? = rows[deviceId]
    override suspend fun all(): List<PeerCandidate> = rows.values.toList()
    override suspend fun remove(deviceId: String) {
        rows.remove(deviceId)
    }

    override suspend fun removeExpired(nowEpochMs: Long) {
        rows.values.removeAll { it.expiresAtEpochMs <= nowEpochMs }
    }

    override suspend fun clear() = rows.clear()
}
