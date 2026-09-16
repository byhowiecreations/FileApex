package com.fileapex.domain.transfer

import com.fileapex.util.TimeUtils
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LiveTransferStats(
    val isActive: Boolean = false,
    val destinationDeviceId: String = "",
    val destinationDeviceName: String = "",
    val currentFileName: String = "",
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

    private class TargetProgress(
        val deviceId: String,
        var deviceName: String,
        var fileName: String
    ) {
        var sentBytes: Long = 0L
        var totalBytes: Long = 0L
        var progress: Float = 0f
        var lastSampleTimeMs: Long = TimeUtils.now()
        var lastSampleBytes: Long = 0L
        var smoothedSpeedBps: Long = 0L

        fun toLiveTransferStats(): LiveTransferStats = LiveTransferStats(
            isActive = true,
            destinationDeviceId = deviceId,
            destinationDeviceName = deviceName,
            currentFileName = fileName,
            sentBytes = sentBytes,
            totalBytes = totalBytes,
            progress = progress,
            speedBytesPerSec = smoothedSpeedBps,
            speedFormatted = formatSpeed(smoothedSpeedBps),
            etaFormatted = formatEta(sentBytes, totalBytes, smoothedSpeedBps)
        )
    }

    private val activeTargetMap = java.util.concurrent.ConcurrentHashMap<String, TargetProgress>()

    private var currentFileName: String = ""
    private var destinationDeviceName: String = ""
    private var lastSampleTimeMs: Long = 0L
    private var lastSampleBytes: Long = 0L
    private var smoothedSpeedBps: Long = 0L

    fun beginTransfer(
        fileName: String = "",
        destinationDeviceName: String = "",
        deviceId: String = ""
    ) {
        if (fileName.isNotBlank()) this.currentFileName = fileName
        if (destinationDeviceName.isNotBlank()) this.destinationDeviceName = destinationDeviceName

        if (deviceId.isNotBlank()) {
            activeTargetMap[deviceId] = TargetProgress(
                deviceId = deviceId,
                deviceName = destinationDeviceName,
                fileName = fileName.ifBlank { this.currentFileName }
            )
        } else {
            activeTransfers.incrementAndGet()
        }

        val count = if (activeTargetMap.isNotEmpty()) activeTargetMap.size else activeTransfers.get()

        lastSampleTimeMs = TimeUtils.now()
        lastSampleBytes = 0L
        smoothedSpeedBps = 0L
        _transferProgressFlow.value = 0.0f
        _isTransferActiveFlow.value = count > 0
        TransferWakeLockCoordinator.acquire()
        _statsFlow.value = LiveTransferStats(
            isActive = count > 0,
            destinationDeviceId = deviceId,
            currentFileName = this.currentFileName,
            destinationDeviceName = this.destinationDeviceName
        )
    }

    fun setTransferContext(fileName: String, destinationDeviceName: String = "", deviceId: String = "") {
        if (fileName.isNotBlank()) this.currentFileName = fileName
        if (destinationDeviceName.isNotBlank()) this.destinationDeviceName = destinationDeviceName
        if (deviceId.isNotBlank()) {
            activeTargetMap[deviceId]?.let {
                if (fileName.isNotBlank()) it.fileName = fileName
                if (destinationDeviceName.isNotBlank()) it.deviceName = destinationDeviceName
            }
        }
        _statsFlow.value = _statsFlow.value.copy(
            currentFileName = this.currentFileName,
            destinationDeviceName = this.destinationDeviceName
        )
    }

    fun updateProgress(sentBytes: Long, totalBytes: Long, deviceId: String = "") {
        if (totalBytes <= 0L) return
        val frac = (sentBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)

        if (deviceId.isNotBlank()) {
            val target = activeTargetMap.getOrPut(deviceId) {
                TargetProgress(deviceId, destinationDeviceName, currentFileName)
            }
            target.sentBytes = sentBytes
            target.totalBytes = totalBytes
            target.progress = frac

            val now = TimeUtils.now()
            val dtMs = (now - target.lastSampleTimeMs).coerceAtLeast(1L)
            val dBytes = (sentBytes - target.lastSampleBytes).coerceAtLeast(0L)
            if (dtMs >= 100L || sentBytes >= totalBytes) {
                val instantBps = (dBytes * 1000L) / dtMs
                target.smoothedSpeedBps = if (target.smoothedSpeedBps == 0L) {
                    instantBps
                } else {
                    ((target.smoothedSpeedBps * 7) + (instantBps * 3)) / 10
                }
                target.lastSampleTimeMs = now
                target.lastSampleBytes = sentBytes
            }
        }

        _transferProgressFlow.value = frac
        val now = TimeUtils.now()
        val dtMs = (now - lastSampleTimeMs).coerceAtLeast(1L)
        val dBytes = (sentBytes - lastSampleBytes).coerceAtLeast(0L)

        // Refresh rolling speed window every 100ms+ for immediate UI reactivity
        if (dtMs >= 100L || sentBytes >= totalBytes) {
            val instantBps = (dBytes * 1000L) / dtMs
            smoothedSpeedBps = if (smoothedSpeedBps == 0L) {
                instantBps
            } else {
                ((smoothedSpeedBps * 7) + (instantBps * 3)) / 10
            }
            lastSampleTimeMs = now
            lastSampleBytes = sentBytes
        }

        val speedStr = formatSpeed(smoothedSpeedBps)
        val etaStr = formatEta(sentBytes, totalBytes, smoothedSpeedBps)

        _statsFlow.value = LiveTransferStats(
            isActive = true,
            destinationDeviceId = deviceId,
            currentFileName = currentFileName,
            destinationDeviceName = destinationDeviceName,
            sentBytes = sentBytes,
            totalBytes = totalBytes,
            progress = frac,
            speedBytesPerSec = smoothedSpeedBps,
            speedFormatted = speedStr,
            etaFormatted = etaStr
        )
    }

    fun endTransfer(deviceId: String = "") {
        if (deviceId.isNotBlank()) {
            activeTargetMap.remove(deviceId)
        } else {
            activeTransfers.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        }
        val count = if (activeTargetMap.isNotEmpty()) {
            activeTargetMap.size
        } else {
            activeTransfers.get()
        }
        _transferProgressFlow.value = if (count > 0) _transferProgressFlow.value else 0.0f
        _isTransferActiveFlow.value = count > 0
        _statsFlow.value = if (count > 0) {
            LiveTransferStats(
                isActive = true,
                currentFileName = currentFileName,
                destinationDeviceName = destinationDeviceName,
                progress = _transferProgressFlow.value,
                speedFormatted = "",
                etaFormatted = ""
            )
        } else {
            LiveTransferStats(isActive = false)
        }
        TransferWakeLockCoordinator.release()
        if (count == 0) {
            currentFileName = ""
            destinationDeviceName = ""
            activeTargetMap.clear()
            activeTransfers.set(0)
            _transferProgressFlow.value = 0.0f
        }
    }

    fun reset() {
        TransferWakeLockCoordinator.releaseAll()
        activeTargetMap.clear()
        activeTransfers.set(0)
        currentFileName = ""
        destinationDeviceName = ""
        _transferProgressFlow.value = 0.0f
        _isTransferActiveFlow.value = false
        _statsFlow.value = LiveTransferStats(isActive = false)
    }

    fun getActiveTransfers(): List<LiveTransferStats> {
        if (activeTargetMap.isNotEmpty()) {
            return activeTargetMap.values.map { it.toLiveTransferStats() }
        }
        val global = _statsFlow.value
        return if (global.isActive) listOf(global) else emptyList()
    }

    fun isTransferActive(): Boolean = if (activeTargetMap.isNotEmpty()) true else activeTransfers.get() > 0

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
