package com.fileapex.domain.clipboard

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Remembers clipboard texts sent through the cloud until the receiving device confirms them with a
 * `clipboard_ack` push, so the sender can say "received" only when that is true.
 */
object ClipboardAckTracker {
    /** A text pushed through the cloud is dropped by the receiver after 15 s, so waiting longer cannot help. */
    const val WAIT_MS = 25_000L

    private val lock = Any()
    private val waiting = mutableMapOf<String, CompletableDeferred<Unit>>()

    private fun key(deviceId: String, capturedAtEpochMs: Long) = "$deviceId|$capturedAtEpochMs"

    /** Call before sending, so an ack that arrives at once is not missed. */
    fun expect(deviceId: String, capturedAtEpochMs: Long): Waiter {
        val key = key(deviceId, capturedAtEpochMs)
        val deferred = CompletableDeferred<Unit>()
        synchronized(lock) { waiting[key] = deferred }
        return Waiter(key, deferred)
    }

    fun onAck(deviceId: String, capturedAtEpochMs: Long) {
        val deferred = synchronized(lock) { waiting.remove(key(deviceId, capturedAtEpochMs)) }
        deferred?.complete(Unit)
    }

    class Waiter internal constructor(private val key: String, private val deferred: CompletableDeferred<Unit>) {
        /** True when the ack arrived within [timeoutMs]. */
        suspend fun await(timeoutMs: Long = WAIT_MS): Boolean {
            val arrived = withTimeoutOrNull(timeoutMs) { deferred.await() } != null
            if (!arrived) cancel()
            return arrived
        }

        fun cancel() {
            synchronized(lock) { waiting.remove(key) }
        }
    }
}
