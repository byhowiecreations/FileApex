package com.fileapex.util

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A preview that is superseded or closed must never write its late result into the screen. */
class LatestJobTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun `starting a new job cancels the one still running`() = runBlocking {
        val latest = LatestJob(scope)
        val written = CopyOnWriteArrayList<String>()
        val firstStarted = CompletableDeferred<Unit>()
        val firstMayFinish = CompletableDeferred<Unit>()
        latest.launch {
            firstStarted.complete(Unit)
            firstMayFinish.await()
            written += "first"
        }
        firstStarted.await()
        val second = latest.launch { written += "second" }
        second.join()
        firstMayFinish.complete(Unit)
        assertEquals(listOf("second"), written.toList())
    }

    @Test
    fun `cancel stops the running job and nothing is written afterwards`() = runBlocking {
        val latest = LatestJob(scope)
        val written = CopyOnWriteArrayList<String>()
        val started = CompletableDeferred<Unit>()
        val mayFinish = CompletableDeferred<Unit>()
        val job = latest.launch {
            started.complete(Unit)
            mayFinish.await()
            written += "late"
        }
        started.await()
        latest.cancel()
        job.join()
        mayFinish.complete(Unit)
        assertTrue(job.isCancelled)
        assertFalse(written.contains("late"))
    }

    @Test
    fun `cancel with nothing running is harmless`() {
        LatestJob(scope).cancel()
        scope.cancel()
    }
}
