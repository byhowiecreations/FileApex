package com.fileapex.domain.transfer

import com.fileapex.util.TimeUtils
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LiveTransferStats(
    val isActive: Boolean = false,
    val sentBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val progress: Float = 0f,
    val speedBytesPerSec: Long = 0L,
    val speedFormatted: String = "",
    val etaFormatted: String = ""
)

/**
 * Tracks in-flight LAN transfers so presence sweeps can yield (battery + throughput),
 * and computes real-time transfer throughput (speed) and dynamic ETA for UI indicators.
 */
object TransferActivityGuard {
    private val activeTransfers = AtomicInteger(0)
    private val _isTransferActiveFlow = MutableStateFlow(false)
    val isTransferActiveFlow: StateFlow<Boolean> = _isTransferActiveFlow.asStateFlow()

    private val _transferProgressFlow = MutableStateFlow(0.0f)
    val transferProgressFlow: StateFlow<Float> = _transferProgressFlow.asStateFlow()

    private val _statsFlow = MutableStateFlow(LiveTransferStats())
    val statsFlow: StateFlow<LiveTransferStats> = _statsFlow.asStateFlow()

    private var lastSampleTimeMs: Long = 0L
    private var lastSampleBytes: Long = 0L
    private var smoothedSpeedBps: Long = 0L

    fun beginTransfer() {
        val count = activeTransfers.incrementAndGet()
        lastSampleTimeMs = TimeUtils.now()
        lastSampleBytes = 0L
        smoothedSpeedBps = 0L
        _transferProgressFlow.value = 0.0f
        _isTransferActiveFlow.value = count > 0
        _statsFlow.value = LiveTransferStats(isActive = count > 0)
    }

    fun updateProgress(sentBytes: Long, totalBytes: Long) {
        if (totalBytes <= 0L) return
        val frac = (sentBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        _transferProgressFlow.value = frac

        val now = TimeUtils.now()
        val dtMs = (now - lastSampleTimeMs).coerceAtLeast(1L)
        val dBytes = (sentBytes - lastSampleBytes).coerceAtLeast(0L)

        // Refresh rolling speed window every 200ms+
        if (dtMs >= 200L || sentBytes >= totalBytes) {
            val instantBps = (dBytes * 1000L) / dtMs
            smoothedSpeedBps = if (smoothedSpeedBps == 0L) {
                instantBps
            } else {
                // Exponential moving average (70% previous, 30% instant) for smooth UI reading
                ((smoothedSpeedBps * 7) + (instantBps * 3)) / 10
            }
            lastSampleTimeMs = now
            lastSampleBytes = sentBytes
        }

        val speedStr = formatSpeed(smoothedSpeedBps)
        val etaStr = formatEta(sentBytes, totalBytes, smoothedSpeedBps)

        _statsFlow.value = LiveTransferStats(
            isActive = true,
            sentBytes = sentBytes,
            totalBytes = totalBytes,
            progress = frac,
            speedBytesPerSec = smoothedSpeedBps,
            speedFormatted = speedStr,
            etaFormatted = etaStr
        )
    }

    fun endTransfer() {
        val count = activeTransfers.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        _transferProgressFlow.value = 1.0f
        _isTransferActiveFlow.value = count > 0
        _statsFlow.value = LiveTransferStats(
            isActive = count > 0,
            progress = 1.0f,
            speedFormatted = "",
            etaFormatted = ""
        )
    }

    fun isTransferActive(): Boolean = activeTransfers.get() > 0

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return ""
        val kb = bytesPerSec / 1024.0
        return if (kb < 1024.0) {
            "${kb.toInt()} KB/s"
        } else {
            val mb = kb / 1024.0
            val rounded = (mb * 10).toInt() / 10.0
            "$rounded MB/s"
        }
    }

    private fun formatEta(sentBytes: Long, totalBytes: Long, bytesPerSec: Long): String {
        if (bytesPerSec <= 0L || sentBytes >= totalBytes) return ""
        val remainingBytes = (totalBytes - sentBytes).coerceAtLeast(0L)
        val seconds = (remainingBytes / bytesPerSec).coerceAtLeast(1L)
        return if (seconds < 60L) {
            "${seconds}s left"
        } else {
            val m = seconds / 60L
            val s = seconds % 60L
            "${m}m ${s}s left"
        }
    }
}
