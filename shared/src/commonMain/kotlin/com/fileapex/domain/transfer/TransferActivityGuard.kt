package com.fileapex.domain.transfer

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks in-flight LAN transfers so presence sweeps can yield (battery + throughput).
 */
object TransferActivityGuard {
    private val activeTransfers = AtomicInteger(0)
    private val _isTransferActiveFlow = MutableStateFlow(false)
    val isTransferActiveFlow: StateFlow<Boolean> = _isTransferActiveFlow.asStateFlow()

    private val _transferProgressFlow = MutableStateFlow(0.0f)
    val transferProgressFlow: StateFlow<Float> = _transferProgressFlow.asStateFlow()

    fun beginTransfer() {
        val count = activeTransfers.incrementAndGet()
        _transferProgressFlow.value = 0.0f
        _isTransferActiveFlow.value = count > 0
    }

    fun updateProgress(sentBytes: Long, totalBytes: Long) {
        if (totalBytes > 0L) {
            val frac = (sentBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            _transferProgressFlow.value = frac
        }
    }

    fun endTransfer() {
        val count = activeTransfers.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        _transferProgressFlow.value = 1.0f
        _isTransferActiveFlow.value = count > 0
    }

    fun isTransferActive(): Boolean = activeTransfers.get() > 0
}


