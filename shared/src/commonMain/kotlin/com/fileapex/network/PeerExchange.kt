package com.fileapex.network

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

/**
 * One request to a peer and the reading of its answer, for callers where null means "no usable answer".
 * The peer policy and an unreachable peer throw [IllegalArgumentException] or [IllegalStateException] and a peer's body
 * is hostile input, so those become null with the reason logged (peer id, class and status only, never an address).
 * Cancellation and every other exception propagate. A failure is logged once until the peer answers again or the
 * reason changes, so a peer that stays offline does not fill the log on every tick.
 */
internal class PeerExchange(private val log: (String) -> Unit = { println(it) }) {
    private val lastFailure = ConcurrentHashMap<String, String>()

    /** [okOnly]: a non-2xx status is a failure; otherwise the body is read whatever the status (a refusal can carry an answer). */
    suspend fun <T : Any> answer(
        peerId: String,
        what: String,
        okOnly: Boolean,
        call: suspend () -> PeerBoundHttpResponse,
        decode: (String) -> T
    ): T? {
        val key = "$peerId/$what"
        val response = try {
            call()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalStateException) {
            return failed(key, "$what to $peerId failed - ${error::class.simpleName}")
        } catch (error: IllegalArgumentException) {
            return failed(key, "$what to $peerId was not sent - ${error::class.simpleName}")
        }
        if (okOnly && response.statusCode !in 200..299) return failed(key, "$peerId answered the $what with ${response.statusCode}")
        return try {
            decode(response.body).also { lastFailure.remove(key) }
        } catch (_: SerializationException) {
            failed(key, "$peerId answered the $what with ${response.statusCode} and an unreadable body")
        }
    }

    private fun failed(key: String, message: String): Nothing? {
        if (lastFailure.put(key, message) != message) log("PeerExchange: $message")
        return null
    }
}
