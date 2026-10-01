package com.fileapex.data.bulletin

import java.util.concurrent.ConcurrentHashMap

/** Per-peer exponential backoff for outbox drains; reset on the first successful batch. */
internal class BulletinPeerBackoff(
    private val initialDelayMs: Long = 30_000L,
    private val maxDelayMs: Long = 15 * 60_000L
) {
    private data class State(val failures: Int, val nextAttemptAtMs: Long)

    private val states = ConcurrentHashMap<String, State>()

    fun canAttempt(deviceId: String, nowMs: Long): Boolean {
        val state = states[deviceId] ?: return true
        return nowMs >= state.nextAttemptAtMs
    }

    fun onSuccess(deviceId: String) {
        states.remove(deviceId)
    }

    /** Returns the delay applied before the next attempt. */
    fun onFailure(deviceId: String, nowMs: Long): Long {
        val updated = states.compute(deviceId) { _, previous ->
            val failures = (previous?.failures ?: 0) + 1
            State(failures, nowMs + delayFor(failures))
        }!!
        return updated.nextAttemptAtMs - nowMs
    }

    private fun delayFor(failures: Int): Long {
        val shift = (failures - 1).coerceIn(0, 20)
        return (initialDelayMs shl shift).coerceAtMost(maxDelayMs)
    }
}
