package com.fileapex.domain.transfer

import com.fileapex.network.TransferRuntime
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferBatchSchedulerTest {

    private val large = TransferRuntime.LARGE_FILE_THRESHOLD_BYTES

    @Test
    fun directoriesRunBeforeFilesParentsFirst() = runBlocking {
        val order = Collections.synchronizedList(mutableListOf<String>())
        fun job(path: String, dir: Boolean) = TransferJob(path, 1L, dir, path) { order += path; path }
        TransferBatchScheduler.runAll(
            listOf(job("A/b/c.txt", false), job("A/b", true), job("A/d.txt", false), job("A", true))
        ) { j, _ -> j.label }
        assertEquals(listOf("A", "A/b"), order.take(2))
        assertEquals(setOf("A/b/c.txt", "A/d.txt"), order.drop(2).toSet())
    }

    @Test
    fun resultsKeepInputOrder() = runBlocking {
        val jobs = (0 until 20).map { index ->
            TransferJob("f$index", (20 - index).toLong(), false, "f$index") {
                delay((index % 3).toLong())
                index
            }
        }
        assertEquals((0 until 20).toList(), TransferBatchScheduler.runAll(jobs) { _, _ -> -1 })
    }

    @Test
    fun smallFilesAreBoundedByWorkerCount() = runBlocking {
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val jobs = (0 until 30).map { index ->
            TransferJob("s$index", 1024L, false, "s$index") {
                peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                delay(5)
                active.decrementAndGet()
            }
        }
        TransferBatchScheduler.runAll(jobs) { _, _ -> 0 }
        assertTrue(peak.get() <= TransferRuntime.smallFileWorkers())
        assertTrue(peak.get() > 1)
    }

    @Test
    fun largeFilesRunOneAtATimeLargestFirst() = runBlocking {
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val order = Collections.synchronizedList(mutableListOf<Long>())
        val sizes = listOf(large, large * 3, large * 2)
        val jobs = sizes.map { size ->
            TransferJob("l$size", size, false, "l$size") {
                peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                order += size
                delay(5)
                active.decrementAndGet()
            }
        }
        TransferBatchScheduler.runAll(jobs) { _, _ -> 0 }
        assertEquals(1, peak.get())
        assertEquals(listOf(large * 3, large * 2, large), order)
    }

    @Test
    fun failureIsIsolatedToItsJob() = runBlocking {
        val jobs = (0 until 6).map { index ->
            TransferJob("f$index", 10L, false, "f$index") {
                if (index == 2) error("boom")
                "ok$index"
            }
        }
        val results = TransferBatchScheduler.runAll(jobs) { job, failure -> "${job.label}:${failure.message}" }
        assertEquals(listOf("ok0", "ok1", "f2:boom", "ok3", "ok4", "ok5"), results)
    }
}
