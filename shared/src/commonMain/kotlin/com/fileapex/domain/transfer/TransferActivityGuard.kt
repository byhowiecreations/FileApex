package com.fileapex.domain.transfer

import com.fileapex.util.TimeUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
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
    val etaFormatted: String = "",
    /** True while a user-cancelable outbound batch is running. */
    val cancelable: Boolean = false
)

/**
 * Tracks in-flight LAN transfers so presence sweeps can yield (battery + throughput),
 * and publishes one batch-wide progress, speed and ETA for UI indicators.
 *
 * Parallel workers report per stream (one file to one device); [statsFlow] sums them against
 * the batch total so the bar only moves forward, and emits at most every [EMIT_INTERVAL_MS].
 */
object TransferActivityGuard {
    private const val EMIT_INTERVAL_MS = 150L
    private const val ANONYMOUS_STREAM = "_"

    private val anonymousActive = AtomicInteger(0)
    private val _isTransferActiveFlow = MutableStateFlow(false)
    val isTransferActiveFlow: StateFlow<Boolean> = _isTransferActiveFlow.asStateFlow()

    private val _transferProgressFlow = MutableStateFlow(0.0f)
    val transferProgressFlow: StateFlow<Float> = _transferProgressFlow.asStateFlow()

    private val _statsFlow = MutableStateFlow(LiveTransferStats())
    val statsFlow: StateFlow<LiveTransferStats> = _statsFlow.asStateFlow()

    private class Stream(
        val deviceId: String,
        @Volatile var deviceName: String,
        @Volatile var fileName: String
    ) {
        @Volatile var sentBytes: Long = 0L
        @Volatile var totalBytes: Long = 0L
    }

    private val streams = ConcurrentHashMap<String, Stream>()
    private val batchTotalBytes = AtomicLong(0L)
    private val batchFinishedBytes = AtomicLong(0L)
    private val cancelableJobs = ConcurrentHashMap.newKeySet<Job>()

    @Volatile private var currentFileName: String = ""
    @Volatile private var destinationDeviceName: String = ""

    private val lastEmitMs = AtomicLong(0L)
    private val publishLock = Any()
    private val speedLock = Any()
    private var lastSampleTimeMs: Long = 0L
    private var lastSampleBytes: Long = 0L
    private var smoothedSpeedBps: Long = 0L

    /**
     * Starts a stream when [deviceId] or [streamKey] is set; otherwise marks an untracked
     * transfer (batch wrapper or inbound upload) as active.
     */
    fun beginTransfer(
        fileName: String = "",
        destinationDeviceName: String = "",
        deviceId: String = "",
        streamKey: String = ""
    ) {
        if (fileName.isNotBlank()) currentFileName = fileName
        if (destinationDeviceName.isNotBlank()) this.destinationDeviceName = destinationDeviceName
        val key = streamKey.ifBlank { deviceId }
        if (key.isNotBlank()) {
            streams[key] = Stream(deviceId, destinationDeviceName, fileName.ifBlank { currentFileName })
        } else {
            anonymousActive.incrementAndGet()
        }
        TransferWakeLockCoordinator.acquire()
        _isTransferActiveFlow.value = true
        publish(force = true)
    }

    fun setTransferContext(fileName: String, destinationDeviceName: String = "", deviceId: String = "") {
        if (fileName.isNotBlank()) currentFileName = fileName
        if (destinationDeviceName.isNotBlank()) this.destinationDeviceName = destinationDeviceName
        if (deviceId.isNotBlank()) {
            streams[deviceId]?.let {
                if (fileName.isNotBlank()) it.fileName = fileName
                if (destinationDeviceName.isNotBlank()) it.deviceName = destinationDeviceName
            }
        }
        publish(force = false)
    }

    /** Bytes the current batch will move in total (files × destinations); called once per planned pass. */
    fun addBatchBytes(bytes: Long) {
        if (bytes > 0L) batchTotalBytes.addAndGet(bytes)
    }

    fun updateProgress(sentBytes: Long, totalBytes: Long, deviceId: String = "", streamKey: String = "") {
        if (totalBytes <= 0L) return
        val key = streamKey.ifBlank { deviceId }.ifBlank { ANONYMOUS_STREAM }
        val stream = streams.getOrPut(key) { Stream(deviceId, destinationDeviceName, currentFileName) }
        stream.sentBytes = sentBytes.coerceIn(0L, totalBytes)
        stream.totalBytes = totalBytes
        currentFileName = stream.fileName.ifBlank { currentFileName }
        publish(force = sentBytes >= totalBytes)
    }

    fun endTransfer(deviceId: String = "", streamKey: String = "") {
        val key = streamKey.ifBlank { deviceId }
        if (key.isNotBlank()) {
            streams.remove(key)?.let { batchFinishedBytes.addAndGet(it.totalBytes.coerceAtLeast(it.sentBytes)) }
        } else if (anonymousActive.updateAndGet { current -> (current - 1).coerceAtLeast(0) } == 0) {
            streams.remove(ANONYMOUS_STREAM)?.let { batchFinishedBytes.addAndGet(it.totalBytes) }
        }
        TransferWakeLockCoordinator.release()
        if (!isTransferActive()) {
            clearBatch()
            return
        }
        publish(force = true)
    }

    fun reset() {
        TransferWakeLockCoordinator.releaseAll()
        streams.clear()
        anonymousActive.set(0)
        cancelableJobs.clear()
        clearBatch()
    }

    /** Registers [job] so the live banner, share sheet and queue can cancel it; returns an unregister handle. */
    fun registerCancelable(job: Job): () -> Unit {
        cancelableJobs += job
        publish(force = true)
        return {
            cancelableJobs -= job
            publish(force = true)
        }
    }

    fun cancelActiveTransfers(): Boolean {
        val jobs = cancelableJobs.toList()
        jobs.forEach { it.cancel() }
        return jobs.isNotEmpty()
    }

    fun getActiveTransfers(): List<LiveTransferStats> {
        val perDevice = streams.values
            .filter { it.deviceId.isNotBlank() }
            .groupBy { it.deviceId }
            .map { (deviceId, group) ->
                val sent = group.sumOf { it.sentBytes }
                val total = group.sumOf { it.totalBytes }
                LiveTransferStats(
                    isActive = true,
                    destinationDeviceId = deviceId,
                    destinationDeviceName = group.first().deviceName,
                    currentFileName = group.last().fileName,
                    sentBytes = sent,
                    totalBytes = total,
                    progress = fraction(sent, total)
                )
            }
        if (perDevice.isNotEmpty()) return perDevice
        val global = _statsFlow.value
        return if (global.isActive) listOf(global) else emptyList()
    }

    fun isTransferActive(): Boolean = streams.isNotEmpty() || anonymousActive.get() > 0

    private fun clearBatch() = synchronized(publishLock) {
        if (isTransferActive()) return@synchronized
        currentFileName = ""
        destinationDeviceName = ""
        batchTotalBytes.set(0L)
        batchFinishedBytes.set(0L)
        synchronized(speedLock) {
            lastSampleTimeMs = 0L
            lastSampleBytes = 0L
            smoothedSpeedBps = 0L
        }
        lastEmitMs.set(0L)
        _transferProgressFlow.value = 0.0f
        _isTransferActiveFlow.value = false
        _statsFlow.value = LiveTransferStats(isActive = false, cancelable = cancelableJobs.isNotEmpty())
    }

    private fun publish(force: Boolean) {
        val now = TimeUtils.now()
        val previous = lastEmitMs.get()
        if (!force && now - previous < EMIT_INTERVAL_MS) return
        if (!lastEmitMs.compareAndSet(previous, now) && !force) return
        synchronized(publishLock) { publishLocked(now) }
    }

    private fun publishLocked(now: Long) {
        val active = streams.values.toList()
        val activeSent = active.sumOf { it.sentBytes }
        val activeTotal = active.sumOf { it.totalBytes }
        val finished = batchFinishedBytes.get()
        val sent = finished + activeSent
        val total = maxOf(batchTotalBytes.get(), finished + activeTotal)
        val speed = sampleSpeed(now, sent)
        val progress = fraction(sent, total)
        val isActive = isTransferActive()
        val devices = active.mapNotNull { it.deviceName.takeIf(String::isNotBlank) }.distinct()

        _isTransferActiveFlow.value = isActive
        _transferProgressFlow.value = if (isActive) progress else 0f
        _statsFlow.value = LiveTransferStats(
            isActive = isActive,
            destinationDeviceId = active.singleOrNull()?.deviceId.orEmpty(),
            destinationDeviceName = devices.joinToString(", ").ifBlank { destinationDeviceName },
            currentFileName = active.lastOrNull()?.fileName?.ifBlank { null } ?: currentFileName,
            sentBytes = sent,
            totalBytes = total,
            progress = progress,
            speedBytesPerSec = speed,
            speedFormatted = formatSpeed(speed),
            etaFormatted = formatEta(sent, total, speed),
            cancelable = cancelableJobs.isNotEmpty()
        )
    }

    private fun sampleSpeed(now: Long, sentBytes: Long): Long = synchronized(speedLock) {
        if (lastSampleTimeMs == 0L || sentBytes < lastSampleBytes) {
            lastSampleTimeMs = now
            lastSampleBytes = sentBytes
            return smoothedSpeedBps
        }
        val dtMs = now - lastSampleTimeMs
        if (dtMs < EMIT_INTERVAL_MS) return smoothedSpeedBps
        val instantBps = ((sentBytes - lastSampleBytes) * 1000L) / dtMs
        smoothedSpeedBps = if (smoothedSpeedBps == 0L) instantBps else ((smoothedSpeedBps * 7) + (instantBps * 3)) / 10
        lastSampleTimeMs = now
        lastSampleBytes = sentBytes
        smoothedSpeedBps
    }

    private fun fraction(sent: Long, total: Long): Float =
        if (total <= 0L) 0f else (sent.toFloat() / total.toFloat()).coerceIn(0f, 1f)

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
