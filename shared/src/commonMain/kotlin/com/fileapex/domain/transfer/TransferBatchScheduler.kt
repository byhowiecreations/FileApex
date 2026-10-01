package com.fileapex.domain.transfer

import com.fileapex.network.TransferRuntime
import com.fileapex.network.transferCatching
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class TransferJob<R : Any>(
    val label: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    val relativePath: String,
    val run: suspend () -> R
)

/**
 * Order for multi-file sends, pastes, and downloads. Directories run first, parents before
 * children. Files at or above [TransferRuntime.LARGE_FILE_THRESHOLD_BYTES] then go one at a
 * time, largest first (the client splits each into ranges), while a small worker pool drains
 * the remaining files alongside. A failing job is converted by [onFailure] and never cancels
 * its siblings.
 */
internal object TransferBatchScheduler {
    suspend fun <R : Any> runAll(
        jobs: List<TransferJob<R>>,
        onFailure: (TransferJob<R>, Throwable) -> R
    ): List<R> {
        if (jobs.isEmpty()) return emptyList()
        val results = ConcurrentHashMap<Int, R>(jobs.size)

        suspend fun execute(index: Int) {
            val job = jobs[index]
            val outcome = transferCatching { job.run() }
            results[index] = outcome.getOrElse { error -> onFailure(job, error) }
        }

        val directories = jobs.indices
            .filter { jobs[it].isDirectory }
            .sortedBy { depthOf(jobs[it].relativePath) }
        val files = jobs.indices.filter { !jobs[it].isDirectory }
        val (large, small) = files.partition { TransferRuntime.isLargeFile(jobs[it].sizeBytes) }
        val largeFirst = large.sortedByDescending { jobs[it].sizeBytes }
        val smallFirst = small.sortedByDescending { jobs[it].sizeBytes }

        withContext(TransferRuntime.outbound) {
            for (index in directories) {
                execute(index)
            }
            coroutineScope {
                if (largeFirst.isNotEmpty()) {
                    launch {
                        for (index in largeFirst) {
                            execute(index)
                        }
                    }
                }
                val next = AtomicInteger(0)
                repeat(TransferRuntime.smallFileWorkers().coerceAtMost(smallFirst.size)) {
                    launch {
                        while (true) {
                            val slot = next.getAndIncrement()
                            if (slot >= smallFirst.size) break
                            execute(smallFirst[slot])
                        }
                    }
                }
            }
        }
        return jobs.indices.map { results.getValue(it) }
    }

    private fun depthOf(relativePath: String): Int = relativePath.count { it == '/' || it == '\\' }
}
