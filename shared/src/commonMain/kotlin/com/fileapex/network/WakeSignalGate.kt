package com.fileapex.network

import com.fileapex.util.TimeUtils

/**
 * Coalesces UDP wake signals so burst packets do not thrash server start.
 * Rate limit window uses [TimeUtils].
 */
object WakeSignalGate {
    const val COALESCE_WINDOW_MS: Long = 250L

    private val lock = Any()
    private var lastAcceptedEpochMs: Long = 0L

    /**
     * A signed packet addressed to this device was seen. It is never held back here, since each sender is limited
     * on its own after its proof is checked; this only makes the plain wake that follows it count as a duplicate.
     */
    fun noteSigned() {
        synchronized(lock) {
            lastAcceptedEpochMs = TimeUtils.now()
        }
    }

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
