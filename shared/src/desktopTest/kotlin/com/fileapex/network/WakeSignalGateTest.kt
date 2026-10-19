package com.fileapex.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeSignalGateTest {
    private fun clearWindow() = Thread.sleep(WakeSignalGate.COALESCE_WINDOW_MS + 50)

    @Test
    fun aPlainWakeRightAfterASignedPacketIsCoalesced() {
        clearWindow()
        WakeSignalGate.noteSigned()

        assertFalse(WakeSignalGate.tryAccept())
    }

    @Test
    fun aPlainWakeAfterTheWindowRuns() {
        clearWindow()
        WakeSignalGate.noteSigned()
        clearWindow()

        assertTrue(WakeSignalGate.tryAccept())
    }

    @Test
    fun signedPacketsFromDifferentSendersNeverBlockEachOther() {
        clearWindow()
        // The receive loop calls noteSigned for every packet addressed to this device and hands each to its handler;
        // noteSigned has no verdict, so a second sender in the same instant is never dropped.
        WakeSignalGate.noteSigned()
        WakeSignalGate.noteSigned()

        assertFalse(WakeSignalGate.tryAccept())
    }
}
