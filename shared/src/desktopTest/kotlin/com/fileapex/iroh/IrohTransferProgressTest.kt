package com.fileapex.iroh

import org.junit.Assert.assertEquals
import org.junit.Test

class IrohTransferProgressTest {
    @Test
    fun theReceiversConfirmationWinsWhenItIsAhead() {
        assertEquals(700L, IrohTransferProgress.shown(confirmed = 700, alreadyOnReceiver = 100, sentByUs = 200, total = 1_000))
    }

    @Test
    fun bytesWeSentCountWhenTheConfirmationPollIsLate() {
        assertEquals(300L, IrohTransferProgress.shown(confirmed = 100, alreadyOnReceiver = 100, sentByUs = 200, total = 1_000))
    }

    @Test
    fun neverExceedsTheTotalOrGoesBackwards() {
        assertEquals(1_000L, IrohTransferProgress.shown(confirmed = 0, alreadyOnReceiver = 900, sentByUs = 500, total = 1_000))
        assertEquals(0L, IrohTransferProgress.shown(confirmed = 0, alreadyOnReceiver = 0, sentByUs = -5, total = 1_000))
    }

    @Test
    fun onlyBridgePortsGetTheLongPollTimeout() {
        IrohBridgePorts.add(40123)
        try {
            assertEquals(20_000L, IrohTransferProgress.pollTimeoutMs("127.0.0.1", 40123, normalMs = 5_000))
            assertEquals(5_000L, IrohTransferProgress.pollTimeoutMs("192.168.1.2", 8080, normalMs = 5_000))
        } finally {
            IrohBridgePorts.clear()
        }
    }
}
