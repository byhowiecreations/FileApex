package com.fileapex.membership

import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Where the state a proven peer shows (on a health answer or with its own probe) arrives. The watch it feeds may pull
 * from that peer, so the hand-over never waits for it: the probe or the answer goes on at once.
 */
object ChainStateInbox {
    @Volatile
    var watch: ChainStateWatch? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    internal var forward: (peerId: String, state: ChainStateField) -> Unit = { peerId, state ->
        watch?.let { w -> scope.launch { cancellableCatching { w.note(peerId, state) } } }
    }

    fun note(peerId: String, state: ChainStateField) = forward(peerId, state)

    @Volatile
    internal var forwardUnreached: (peerId: String) -> Unit = { peerId -> watch?.unreached(peerId) }

    /** A health probe of [peerId] failed on every attempt. */
    fun unreached(peerId: String) = forwardUnreached(peerId)
}
