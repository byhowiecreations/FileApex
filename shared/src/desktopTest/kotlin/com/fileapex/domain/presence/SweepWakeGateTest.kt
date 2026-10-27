package com.fileapex.domain.presence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepWakeGateTest {
    private val floor = LanPresenceTiming.SWEEP_WAKE_FLOOR_MS

    @Test
    fun sweepWakeIsFlooredThenAllowedAgain() {
        val gate = SweepWakeGate()
        assertTrue(gate.claimSweepWake(1_000L))
        assertFalse(gate.claimSweepWake(1_000L + floor - 1))
        assertTrue(gate.claimSweepWake(1_000L + floor))
    }

    @Test
    fun helloForTheSameNetworkIsFlooredButAnotherNetworkGoesOut() {
        val gate = SweepWakeGate()
        assertTrue(gate.claimHello("wifi-a", 5_000L))
        assertFalse(gate.claimHello("wifi-a", 5_000L + floor - 1))
        assertTrue(gate.claimHello("wifi-b", 5_001L))
        assertTrue(gate.claimHello("wifi-a", 5_001L + floor))
    }

    @Test
    fun aHelloHoldsBackTheSweepsOwnWake() {
        val gate = SweepWakeGate()
        assertTrue(gate.claimHello("wifi-a", 9_000L))
        assertFalse(gate.claimSweepWake(9_000L + 1))
    }
}
