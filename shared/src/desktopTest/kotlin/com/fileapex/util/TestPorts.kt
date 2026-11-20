package com.fileapex.util

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ports for tests that start a server and connect to it later. Asking the OS for a free port and releasing it
 * leaves a gap in which another test JVM can take it, and the test then talks to the wrong server. Each test JVM
 * (Gradle worker) owns its own slice of a block below the OS ephemeral range, so parallel forks cannot collide.
 */
object TestPorts {
    const val BASE_PORT = 12_000
    const val SLOTS = 16
    const val PORTS_PER_SLOT = 1_000

    private val cursor = AtomicInteger(0)

    internal fun currentWorker(): Int = System.getProperty("org.gradle.test.worker")?.toIntOrNull() ?: 0

    internal fun rangeFor(worker: Int): IntRange {
        val first = BASE_PORT + Math.floorMod(worker - 1, SLOTS) * PORTS_PER_SLOT
        return first until first + PORTS_PER_SLOT
    }

    /** The next port of this JVM's slice that nothing is listening on. */
    fun next(): Int {
        val range = rangeFor(currentWorker())
        repeat(PORTS_PER_SLOT) {
            val port = range.first + Math.floorMod(cursor.getAndIncrement(), PORTS_PER_SLOT)
            if (isFree(port)) return port
        }
        error("No free test port in $range")
    }

    private fun isFree(port: Int): Boolean = try {
        ServerSocket(port, 1, InetAddress.getLoopbackAddress()).use { }
        ServerSocket(port).use { }
        true
    } catch (_: IOException) {
        false
    }
}
