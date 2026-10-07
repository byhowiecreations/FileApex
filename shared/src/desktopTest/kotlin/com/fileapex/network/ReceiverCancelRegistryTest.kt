package com.fileapex.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverCancelRegistryTest {
    @Test
    fun markedTransactionIsReportedCancelled() {
        ReceiverCancelRegistry.mark("tx-cancel-1")
        assertTrue(ReceiverCancelRegistry.isCancelled("tx-cancel-1"))
    }

    @Test
    fun otherTransactionsAndBlankKeysAreNotCancelled() {
        ReceiverCancelRegistry.mark("tx-cancel-2")
        assertFalse(ReceiverCancelRegistry.isCancelled("tx-other"))
        assertFalse(ReceiverCancelRegistry.isCancelled(""))
        ReceiverCancelRegistry.mark("")
        assertFalse(ReceiverCancelRegistry.isCancelled(""))
    }
}

class InFlightSegmentBytesTest {
    @Test
    fun sumsUnrecordedBytesPerPartAndClears() {
        InFlightSegmentBytes.update("part-a", 0L, 100L)
        InFlightSegmentBytes.update("part-a", 1000L, 50L)
        InFlightSegmentBytes.update("part-b", 0L, 7L)
        org.junit.Assert.assertEquals(150L, InFlightSegmentBytes.sum("part-a"))
        InFlightSegmentBytes.clear("part-a", 0L)
        org.junit.Assert.assertEquals(50L, InFlightSegmentBytes.sum("part-a"))
        InFlightSegmentBytes.clear("part-a", 1000L)
        InFlightSegmentBytes.clear("part-b", 0L)
        org.junit.Assert.assertEquals(0L, InFlightSegmentBytes.sum("part-a"))
    }
}
