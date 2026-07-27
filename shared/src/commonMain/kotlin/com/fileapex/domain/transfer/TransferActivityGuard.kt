package com.fileapex.domain.transfer

import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks in-flight LAN transfers so presence sweeps can yield (battery + throughput).
 */
object TransferActivityGuard {
    private val activeTransfers = AtomicInteger(0)

    fun beginTransfer() {
        activeTransfers.incrementAndGet()
    }

    fun endTransfer() {
        activeTransfers.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }

    fun isTransferActive(): Boolean = activeTransfers.get() > 0
}
