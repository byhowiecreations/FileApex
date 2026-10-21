package com.fileapex.domain.clipboard

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardAckTrackerTest {
    @Test
    fun anAckForTheSameDeviceAndTimeConfirms() = runBlocking {
        val waiter = ClipboardAckTracker.expect("device-a", 1000L)
        ClipboardAckTracker.onAck("device-a", 1000L)
        assertTrue(waiter.await(500L))
    }

    @Test
    fun anAckThatArrivesBeforeTheWaitStartsIsNotLost() = runBlocking {
        val waiter = ClipboardAckTracker.expect("device-b", 2000L)
        ClipboardAckTracker.onAck("device-b", 2000L)
        Thread.sleep(50)
        assertTrue(waiter.await(500L))
    }

    @Test
    fun anAckForAnotherDeviceOrAnotherTextDoesNotConfirm() = runBlocking {
        val waiter = ClipboardAckTracker.expect("device-c", 3000L)
        ClipboardAckTracker.onAck("device-d", 3000L)
        ClipboardAckTracker.onAck("device-c", 3001L)
        assertFalse(waiter.await(150L))
    }

    @Test
    fun noAckMeansNotConfirmedAndALateAckIsIgnored() = runBlocking {
        val waiter = ClipboardAckTracker.expect("device-e", 4000L)
        assertFalse(waiter.await(100L))
        ClipboardAckTracker.onAck("device-e", 4000L)
    }

    @Test
    fun aCancelledWaiterIsForgotten() = runBlocking {
        val waiter = ClipboardAckTracker.expect("device-f", 5000L)
        waiter.cancel()
        ClipboardAckTracker.onAck("device-f", 5000L)
        assertFalse(waiter.await(100L))
    }
}
