package com.fileapex.domain.transfer

import com.fileapex.util.TimeUtils
import java.net.Socket
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
    val cancelable: Boolean = false,
    /** True while the active transfer goes through the Google Drive relay (Cancel then asks what to do with Drive). */
    val driveRelay: Boolean = false
)

/**
 * Tracks in-flight LAN transfers so presence sweeps can yield (battery + throughput),
 * and publishes one batch-wide progress, speed and ETA for UI indicators.
 *
 * Parallel workers report per stream (one file to one device); [statsFlow] sums them against
 * the batch total so the bar only moves forward, and emits at most every [EMIT_INTERVAL_MS].
 */
/** Marks a coroutine tree as belonging to one queue row, so Cancel can target just that row. */
class TransferOwner(val id: String) : kotlin.coroutines.AbstractCoroutineContextElement(TransferOwner) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<TransferOwner>
}

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

    /** Last time any stream of the batch moved forward; 0 when nothing is running. */
    @Volatile
    private var lastProgressAtMs = 0L

    /** Milliseconds since any byte moved in the running batch, so the UI can say a transfer went quiet. */
    fun millisSinceProgress(): Long {
        val last = lastProgressAtMs
        return if (last == 0L) 0L else (TimeUtils.now() - last).coerceAtLeast(0L)
    }
    private val batchTotalBytes = AtomicLong(0L)
    private val batchFinishedBytes = AtomicLong(0L)
    /** Job or socket -> owner id (a queue row id, or blank when it belongs to no row). */
    private val cancelableJobs = ConcurrentHashMap<Job, String>()
    private val transferSockets = ConcurrentHashMap<Socket, String>()
    private val cancelledOwners = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var cancelRequested = false

    private val driveRelayActive = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var removeFromDriveRequested = false

    private val _driveCancelChoicePending = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** True while the app should ask whether a Drive transfer's Cancel keeps or removes the Drive copy. */
    val driveCancelChoicePending: kotlinx.coroutines.flow.StateFlow<Boolean> =
        _driveCancelChoicePending

    /**
     * Cancel as the user taps it. A Drive-relay transfer asks first ("try later" vs "remove from Drive");
     * everything else cancels at once.
     */
    fun requestUserCancel(owner: String = ""): Boolean {
        if (driveRelayActive.get() > 0 && cancelableJobs.isNotEmpty()) {
            driveCancelOwner = owner
            _driveCancelChoicePending.value = true
            return true
        }
        return if (owner.isNotEmpty()) cancelOwner(owner) else cancelActiveTransfers()
    }

    @Volatile
    private var driveCancelOwner = ""

    /** [removeFromDrive] null dismisses the question and keeps the transfer going. */
    fun resolveDriveCancelChoice(removeFromDrive: Boolean?) {
        _driveCancelChoicePending.value = false
        if (removeFromDrive == null) return
        val owner = driveCancelOwner
        driveCancelOwner = ""
        if (owner.isNotEmpty()) cancelOwner(owner, removeFromDrive) else cancelActiveTransfers(removeFromDrive)
    }

    fun beginDriveRelay() {
        driveRelayActive.incrementAndGet()
        publish(force = true)
    }

    fun endDriveRelay() {
        driveRelayActive.updateAndGet { (it - 1).coerceAtLeast(0) }
        publish(force = true)
    }

    /** True when the user chose "cancel and remove from Drive"; Drive code reads this while unwinding. */
    fun removeFromDriveRequested(): Boolean = removeFromDriveRequested

    /** Reads and clears the remove-from-Drive request (the queue owner consumes it once). */
    fun consumeRemoveFromDrive(): Boolean {
        val requested = removeFromDriveRequested
        removeFromDriveRequested = false
        return requested
    }

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
        val moved = sentBytes.coerceIn(0L, totalBytes)
        if (moved > stream.sentBytes || lastProgressAtMs == 0L) lastProgressAtMs = TimeUtils.now()
        stream.sentBytes = moved
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
        transferSockets.keys.forEach { socket -> runCatching { socket.close() } }
        transferSockets.clear()
        cancelledOwners.clear()
        cancelRequested = false
        clearBatch()
    }

    /**
     * Registers [job] so the live banner, share sheet and queue can cancel it; returns an unregister handle.
     * [owner] ties it to one queue row so that row can be cancelled without touching the others.
     */
    fun registerCancelable(job: Job, owner: String = ""): () -> Unit {
        cancelableJobs[job] = owner
        publish(force = true)
        return {
            cancelableJobs.remove(job)
            finishCancelIfIdle()
            publish(force = true)
        }
    }

    fun trackTransferSocket(socket: Socket, owner: String = "") {
        transferSockets[socket] = owner
        if (cancelRequested || (owner.isNotEmpty() && owner in cancelledOwners)) {
            runCatching { socket.close() }
        }
    }

    fun releaseTransferSocket(socket: Socket) {
        transferSockets.remove(socket)
        finishCancelIfIdle()
    }

    fun transferCancelRequested(owner: String = ""): Boolean =
        cancelRequested || (owner.isNotEmpty() && owner in cancelledOwners)

    /**
     * Closes in-flight transfer sockets, then cancels the batch job.
     * A blocked write does not see cancellation until its socket is closed.
     */
    fun cancelActiveTransfers(removeFromDrive: Boolean = false): Boolean {
        val jobs = cancelableJobs.keys.toList()
        val sockets = transferSockets.keys.toList()
        if (jobs.isEmpty() && sockets.isEmpty()) return false
        removeFromDriveRequested = removeFromDrive
        cancelRequested = true
        sockets.forEach { socket -> runCatching { socket.close() } }
        abortInFlightPlatformTransfers()
        jobs.forEach { job -> job.cancel() }
        return true
    }

    /** Cancels only what belongs to [owner]; every other transfer keeps running. */
    fun cancelOwner(owner: String, removeFromDrive: Boolean = false): Boolean {
        if (owner.isEmpty()) return false
        val jobs = cancelableJobs.filterValues { it == owner }.keys.toList()
        val sockets = transferSockets.filterValues { it == owner }.keys.toList()
        if (jobs.isEmpty() && sockets.isEmpty()) return false
        removeFromDriveRequested = removeFromDrive
        cancelledOwners += owner
        sockets.forEach { socket -> runCatching { socket.close() } }
        jobs.forEach { job -> job.cancel() }
        return true
    }

    private fun finishCancelIfIdle() {
        if (cancelableJobs.isEmpty() && transferSockets.isEmpty()) {
            cancelRequested = false
            removeFromDriveRequested = false
        }
        cancelledOwners.removeIf { owner ->
            cancelableJobs.values.none { it == owner } && transferSockets.values.none { it == owner }
        }
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
        lastProgressAtMs = 0L
        _statsFlow.value = LiveTransferStats(
            isActive = false,
            cancelable = cancelableJobs.isNotEmpty(),
            driveRelay = driveRelayActive.get() > 0
        )
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
            cancelable = cancelableJobs.isNotEmpty(),
            driveRelay = driveRelayActive.get() > 0
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
