package com.fileapex.data.device

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
