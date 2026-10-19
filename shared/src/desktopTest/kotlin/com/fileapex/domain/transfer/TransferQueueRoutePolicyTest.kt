package com.fileapex.domain.transfer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferQueueRoutePolicyTest {
    @Test
    fun aRowNeverTriedYetIsTheUsersOwnSendAndMayProbeAnOfflinePeer() {
        assertTrue(TransferQueueRoutePolicy.mayProbeOfflinePeer(lastAttemptEpochMs = 0L))
    }

    @Test
    fun aRowThatAlreadyFailedWaitsForThePeerToComeOnline() {
        assertFalse(TransferQueueRoutePolicy.mayProbeOfflinePeer(lastAttemptEpochMs = 1_760_000_000_000L))
    }
}
