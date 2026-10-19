package com.fileapex.domain.transfer

/**
 * A queued row's first attempt is the user's own send (or their retry, which resets the attempt time), so it may
 * try a peer that shows offline. Later attempts are background retries: they wait for the peer to come online.
 */
object TransferQueueRoutePolicy {
    fun mayProbeOfflinePeer(lastAttemptEpochMs: Long): Boolean = lastAttemptEpochMs == 0L
}
