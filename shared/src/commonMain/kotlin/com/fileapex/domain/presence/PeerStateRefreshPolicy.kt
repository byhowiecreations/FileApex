package com.fileapex.domain.presence

/**
 * When a presence probe should also fetch the peer's full state. A peer that reports its membership version in
 * the health answer is fetched when the version changed (plus a long safety refresh); an older build that reports
 * none keeps the fixed refresh.
 */
object PeerStateRefreshPolicy {
    fun shouldFetch(peerVersion: Long?, seenVersion: Long?, ageMs: Long?, missingMetadata: Boolean): Boolean {
        if (ageMs == null) return true
        if (missingMetadata && ageMs >= LanPresenceTiming.NODE_STATE_MISSING_RETRY_MS) return true
        if (peerVersion == null) return ageMs >= LanPresenceTiming.NODE_STATE_REFRESH_TTL_MS
        if (peerVersion != seenVersion) return true
        return ageMs >= LanPresenceTiming.NODE_STATE_VERSIONED_SAFETY_MS
    }
}
