package com.fileapex.util

import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TestPortsTest {
    @Test
    fun rangesOfDifferentWorkersNeverOverlap() {
        val ranges = (1..TestPorts.SLOTS).map { TestPorts.rangeFor(it) }
        for (a in ranges.indices) for (b in a + 1 until ranges.size) {
            assertTrue("slots $a and $b overlap", ranges[a].last < ranges[b].first || ranges[b].last < ranges[a].first)
        }
    }

    @Test
    fun aWorkerBeyondTheLastSlotWrapsInsideTheWholeBlock() {
        val wrapped = TestPorts.rangeFor(TestPorts.SLOTS + 3)
        assertEquals(TestPorts.rangeFor(3), wrapped)
        assertTrue(wrapped.first >= TestPorts.BASE_PORT)
        assertTrue(wrapped.last < TestPorts.BASE_PORT + TestPorts.SLOTS * TestPorts.PORTS_PER_SLOT)
    }

    @Test
    fun theBlockStaysBelowTheOperatingSystemEphemeralPorts() {
        assertTrue(TestPorts.BASE_PORT + TestPorts.SLOTS * TestPorts.PORTS_PER_SLOT <= 32_768)
    }

    @Test
    fun portsOfOneProcessAreDistinctAndInsideItsRange() {
        val range = TestPorts.rangeFor(TestPorts.currentWorker())
        val ports = List(50) { TestPorts.next() }
        assertEquals(ports.size, ports.toSet().size)
        assertTrue(ports.all { it in range })
    }

    @Test
    fun aPortIsFreeToBindOnLoopbackAndOnAllInterfaces() {
        val port = TestPorts.next()
        ServerSocket(port, 1, InetAddress.getLoopbackAddress()).use { }
        ServerSocket(port).use { }
    }

    @Test
    fun aPortThatIsInUseIsSkipped() {
        val taken = TestPorts.next()
        ServerSocket(taken).use {
            val following = List(20) { TestPorts.next() }
            assertFalse(taken in following)
        }
    }

    @Test
    fun theSameWorkerGetsTheSameRangeAndDifferentWorkersGetDifferentOnes() {
        assertEquals(TestPorts.rangeFor(2), TestPorts.rangeFor(2))
        assertNotEquals(TestPorts.rangeFor(1), TestPorts.rangeFor(2))
    }
}
