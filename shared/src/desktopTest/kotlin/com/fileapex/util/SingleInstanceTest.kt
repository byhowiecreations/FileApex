package com.fileapex.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SingleInstanceTest {
    @Test
    fun manyThreadsAskingTogetherBuildTheValueOnceAndAllGetTheSameOne() {
        val builds = AtomicInteger()
        val holder = SingleInstance {
            builds.incrementAndGet()
            Thread.sleep(150)
            Any()
        }
        val start = CountDownLatch(1)
        val results = arrayOfNulls<Any>(8)
        val threads = (0 until 8).map { n -> Thread { start.await(); results[n] = holder.get() } }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals("the builder ran more than once", 1, builds.get())
        results.forEach { assertSame(results[0], it) }
    }
}
