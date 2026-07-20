package com.omninode.network

import com.omninode.util.TimeUtils

/**
 * Coalesces UDP wake signals so burst packets do not thrash server start.
 * Rate limit window uses [TimeUtils] (SSOT clock).
 */
object WakeSignalGate {
    const val COALESCE_WINDOW_MS: Long = 250L

    private val lock = Any()
    private var lastAcceptedEpochMs: Long = 0L

    /**
     * @return true when this signal should run a wake/process action.
     */
    fun tryAccept(): Boolean {
        synchronized(lock) {
            if (lastAcceptedEpochMs > 0L &&
                TimeUtils.millisSince(lastAcceptedEpochMs) < COALESCE_WINDOW_MS
            ) {
                return false
            }
            lastAcceptedEpochMs = TimeUtils.now()
            return true
        }
    }
}
