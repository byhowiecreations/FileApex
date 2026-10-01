package com.fileapex.network

import com.fileapex.platform.isTransferLowPowerMode
import com.fileapex.util.cancellableCatching
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Semaphore

/**
 * Thread pools, stream limits, and size thresholds for LAN file transfers.
 *
 * Only leaf byte streams (one socket moving file bytes) take a [streamBudget] permit, and a
 * holder never asks for a second one. Fan-out writers fed by a shared producer must not take
 * permits: a writer parked on the semaphore would stall the producer and every sibling.
 */
object TransferRuntime {
    const val LARGE_FILE_THRESHOLD_BYTES = 64L * 1024L * 1024L
    const val TOTAL_STREAM_BUDGET = 8
    const val CHECKPOINT_BYTES = 16L * 1024L * 1024L

    private const val SEGMENTS_PER_LARGE_FILE = 4
    private const val LOW_POWER_SEGMENTS = 2
    private const val SMALL_FILE_WORKERS = 4
    private const val LOW_POWER_SMALL_FILE_WORKERS = 2
    private const val IDLE_THREAD_KEEPALIVE_SECONDS = 30L

    val streamBudget = Semaphore(TOTAL_STREAM_BUDGET)

    // Sized above the stream budget: relay segments hold a reader and a writer thread each,
    // and fan-out writers sit outside the budget.
    val outbound: CoroutineDispatcher = boundedPool("fileapex-transfer-out", 24)

    val inbound: CoroutineDispatcher = boundedPool("fileapex-transfer-in", 16)

    fun segmentsFor(sizeBytes: Long): Int = when {
        sizeBytes < LARGE_FILE_THRESHOLD_BYTES -> 1
        isTransferLowPowerMode() -> LOW_POWER_SEGMENTS
        else -> SEGMENTS_PER_LARGE_FILE
    }

    fun smallFileWorkers(): Int =
        if (isTransferLowPowerMode()) LOW_POWER_SMALL_FILE_WORKERS else SMALL_FILE_WORKERS

    fun isLargeFile(sizeBytes: Long): Boolean = sizeBytes >= LARGE_FILE_THRESHOLD_BYTES

    private fun boundedPool(name: String, threads: Int): CoroutineDispatcher {
        val counter = AtomicInteger(0)
        val executor = ThreadPoolExecutor(
            threads,
            threads,
            IDLE_THREAD_KEEPALIVE_SECONDS,
            TimeUnit.SECONDS,
            LinkedBlockingQueue()
        ) { runnable ->
            Thread(runnable, "$name-${counter.incrementAndGet()}").apply { isDaemon = true }
        }
        executor.allowCoreThreadTimeOut(true)
        return executor.asCoroutineDispatcher()
    }
}

internal inline fun <T> transferCatching(block: () -> T): Result<T> = cancellableCatching(block)

/** No bind candidate could open a TCP connection to the peer endpoint. */
class PeerUnreachableException(message: String) : IllegalStateException(message)

/** The user stopped the batch; the queue keeps the item for a manual retry instead of re-sending it. */
class TransferCancelledException : IllegalStateException(com.fileapex.i18n.AppI18n.t("transfer_cancelled"))

/** Source no longer matches the size the transfer was planned for; retrying cannot help. */
internal class SourceSizeChangedException(message: String) : IllegalStateException(message)
