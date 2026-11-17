package com.fileapex.domain.presence

import com.fileapex.util.TimeUtils

/**
 * A paired peer's goodbye marks it offline right away, with this device's own clock. A sender that is not a paired
 * device changes nothing. Whatever reaches the peer afterwards (a probe, discovery, its own request) brings it back.
 */
internal class PresenceReceiver(
    private val book: ReachabilityBook,
    private val isPairedPeer: suspend (String) -> Boolean,
    private val now: () -> Long = TimeUtils::now
) {
    suspend fun onGoodbye(senderDeviceId: String): Boolean {
        val id = senderDeviceId.trim()
        if (id.isEmpty() || !isPairedPeer(id)) return false
        book.markDown(id, now())
        return true
    }
}
