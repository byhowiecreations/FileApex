package com.fileapex.friends

import com.fileapex.friends.transfer.FriendsWire
import com.fileapex.iroh.IrohStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Removing a friend only forgets them on this device. This tells an online friend so their card can say they were removed,
 * and remembers which friends told us. Memory only: a friend who was offline is not told, and the mark is gone after a restart.
 */
class FriendRemovalNotice(private val scope: CoroutineScope) {
    private val removedYouInternal = MutableStateFlow<Set<String>>(emptySet())
    val removedYou: StateFlow<Set<String>> = removedYouInternal.asStateFlow()

    fun heard(friendKey: String) = removedYouInternal.update { it + friendKey }

    /** Brings up the connection to a friend, unless they already removed us (nothing to reach), and notes it when they close it saying we are not their friend. */
    suspend fun connect(friendKey: String, endpoint: suspend () -> FriendsEndpoint) {
        if (friendKey in removedYouInternal.value) return
        val ep = endpoint()
        ep.connect(friendKey)
        if (ep.refusedBy(friendKey)) heard(friendKey)
    }

    /** Clears the mark. Unless the friend removed us first, says goodbye in the background over the running endpoint (open returns null when there is none); the caller never waits for it. */
    fun removed(friendKey: String, open: suspend (String) -> IrohStream?) {
        val theyLeftFirst = friendKey in removedYouInternal.value
        removedYouInternal.update { it - friendKey }
        if (!theyLeftFirst) scope.launch { sayGoodbye(friendKey, open) }
    }

    private suspend fun sayGoodbye(friendKey: String, open: suspend (String) -> IrohStream?) {
        var stream: IrohStream? = null
        try {
            val done = withTimeoutOrNull(GOODBYE_TIMEOUT_MS) {
                val opened = open(friendKey) ?: return@withTimeoutOrNull true
                stream = opened
                FriendsWire.writeRaw(opened, byteArrayOf(KIND.toByte()))
                opened.finish()
                true
            }
            if (done != true) { stream?.abort(); println("FriendsHub: goodbye to ${friendKey.take(4)}... timed out") }
        } catch (e: CancellationException) {
            stream?.abort()
            throw e
        } catch (e: IOException) {
            stream?.abort()
            println("FriendsHub: goodbye to ${friendKey.take(4)}... not delivered :: ${e::class.simpleName}")
        }
    }

    companion object {
        /** First byte of the goodbye stream; nothing follows it. */
        const val KIND = 4
        private const val GOODBYE_TIMEOUT_MS = 10_000L
    }
}
