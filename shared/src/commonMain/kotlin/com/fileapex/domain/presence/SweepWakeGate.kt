package com.fileapex.domain.presence

import java.util.concurrent.atomic.AtomicLong

internal class SweepWakeGate {
    private val lastSweepWakeAtMs = AtomicLong(0L)
    private val helloLock = Any()
    private var lastHello: Pair<String, Long>? = null

    /** Sweep-driven UDP wakes are floored; on-demand prime and connect wakes are not. */
    fun claimSweepWake(now: Long): Boolean {
        val previous = lastSweepWakeAtMs.get()
        if (previous > 0L && now - previous < LanPresenceTiming.SWEEP_WAKE_FLOOR_MS) return false
        return lastSweepWakeAtMs.compareAndSet(previous, now)
    }

    /** False when a hello went out for the same network inside the floor. A hello also holds back the sweep's own wake. */
    fun claimHello(networkKey: String, now: Long): Boolean {
        synchronized(helloLock) {
            val last = lastHello
            if (last != null && last.first == networkKey && now - last.second < LanPresenceTiming.SWEEP_WAKE_FLOOR_MS) return false
            lastHello = networkKey to now
        }
        lastSweepWakeAtMs.set(now)
        return true
    }
}
