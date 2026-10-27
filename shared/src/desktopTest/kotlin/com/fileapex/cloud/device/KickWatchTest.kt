package com.fileapex.cloud.device

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class KickWatchTest {
    private var clock = 1_000_000L
    private var checks = 0
    private var linked = true
    private val pauses = mutableListOf<Long>()

    private fun watch(unlinkAfter: Int = Int.MAX_VALUE) = KickWatch(
        check = { checks++; if (checks >= unlinkAfter) linked = false },
        stillLinked = { linked },
        pause = { pauses += it },
        now = { clock }
    )

    @Test
    fun asksFirebaseAtEachDelayWhileTheDeviceStaysLinked() = runBlocking {
        watch().run()
        assertEquals(4, checks)
        assertEquals(listOf(5_000L, 30_000L, 120_000L), pauses)
    }

    @Test
    fun stopsAsSoonAsFirebaseSaysTheUserIsDisabled() = runBlocking {
        watch(unlinkAfter = 2).run()
        assertEquals(2, checks)
    }

    @Test
    fun doesNothingWhenGoogleIsNotLinked() = runBlocking {
        linked = false
        watch().run()
        assertEquals(0, checks)
    }

    @Test
    fun aSecondTriggerRightAfterIsIgnoredButALaterOneRuns() = runBlocking {
        val w = watch()
        w.run()
        w.run()
        assertEquals(4, checks)
        clock += 61_000L
        w.run()
        assertEquals(8, checks)
    }
}
