package com.fileapex.domain.presence

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tells peers this device is leaving. Peers are told in parallel and the whole call is held to [budgetMs], so quitting
 * is never delayed by a peer that does not answer. A peer that fails or times out is simply not told.
 */
internal class PresenceNotifier(
    private val send: suspend (PairedDeviceEntity) -> Boolean,
    private val budgetMs: Long = DEFAULT_BUDGET_MS
) {
    suspend fun sayGoodbye(peers: List<PairedDeviceEntity>) {
        if (peers.isEmpty()) return
        withTimeoutOrNull(budgetMs) {
            coroutineScope {
                peers.map { peer -> async { cancellableCatching { send(peer) }.getOrDefault(false) } }.awaitAll()
            }
        }
    }

    companion object {
        const val DEFAULT_BUDGET_MS = 600L
    }
}
