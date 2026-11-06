package com.fileapex.domain.presence

import java.util.concurrent.atomic.AtomicLong

/** Counts the background Iroh probes since the app started, so their cost can be checked against the battery stats. */
class PresenceProbeStats {
    data class Snapshot(val sent: Long, val failed: Long, val skipped: Long, val forcedRounds: Long)

    private val sent = AtomicLong()
    private val failed = AtomicLong()
    private val skipped = AtomicLong()
    private val forced = AtomicLong()

    fun sent() { sent.incrementAndGet() }
    fun failed() { failed.incrementAndGet() }
    fun skippedByBackoff() { skipped.incrementAndGet() }
    fun forcedRound() { forced.incrementAndGet() }

    fun snapshot() = Snapshot(sent.get(), failed.get(), skipped.get(), forced.get())

    companion object {
        /** The counters the app shows; tests use their own instance. */
        val shared = PresenceProbeStats()
    }
}
