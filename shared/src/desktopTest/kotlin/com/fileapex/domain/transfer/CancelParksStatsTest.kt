package com.fileapex.domain.transfer

import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live banner says pause for a queue backed send (owner set) and cancel for a receive (no owner). */
class CancelParksStatsTest {
    @After
    fun tearDown() = TransferActivityGuard.reset()

    @Test
    fun aQueueOwnedSendParksOnCancel() {
        val unregister = TransferActivityGuard.registerCancelable(Job(), owner = "queue-row-1")
        assertTrue(TransferActivityGuard.statsFlow.value.cancelable)
        assertTrue(TransferActivityGuard.statsFlow.value.parksOnCancel)
        unregister()
        assertFalse(TransferActivityGuard.statsFlow.value.parksOnCancel)
    }

    @Test
    fun aReceiveWithNoOwnerDoesNot() {
        val unregister = TransferActivityGuard.registerCancelable(Job())
        assertTrue(TransferActivityGuard.statsFlow.value.cancelable)
        assertFalse(TransferActivityGuard.statsFlow.value.parksOnCancel)
        unregister()
    }
}
