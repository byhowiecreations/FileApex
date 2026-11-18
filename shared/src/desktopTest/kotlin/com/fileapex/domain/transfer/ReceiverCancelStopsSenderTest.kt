package com.fileapex.domain.transfer

import com.fileapex.i18n.AppI18n
import com.fileapex.network.PeerUnreachableException
import com.fileapex.network.TransferCancelledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A receiver that cancels ends the send. It is not a failure the queue retries as a new transaction. */
class ReceiverCancelStopsSenderTest {
    private val peer = MultiCopyDestination.RemoteDevice("peer-1", "Honor", "192.0.2.10", 8080, "/x")
    private val option = MultiCopyDeviceOption("peer-1", "Honor", false, "192.0.2.10", 8080, "/x")
    private val source = MultiCopySource.Local("a.bin", 10L, "/tmp/a.bin")

    @Test
    fun aReceiverCancelIsReportedAsCancelledNotAsAnOrdinaryFailure() {
        val outcome = writerOutcomeFor(peer, TransferCancelledException())
        assertTrue(outcome.cancelledByReceiver)
        assertFalse(outcome.unreachable)
    }

    @Test
    fun otherFailuresAreNotReceiverCancels() {
        assertFalse(writerOutcomeFor(peer, PeerUnreachableException("down")).cancelledByReceiver)
        assertFalse(writerOutcomeFor(peer, IllegalStateException("boom")).cancelledByReceiver)
        assertNull(writerOutcomeFor(peer, null).errorMessage)
    }

    @Test
    fun theBatchNamesTheReceiverCancelAndIsNotAllFailed() {
        val result = MultiCopyResult(
            fileName = "a.bin",
            succeededDeviceIds = emptySet(),
            failures = mapOf("peer-1" to "Transfer cancelled"),
            cancelledByReceiverDeviceIds = setOf("peer-1")
        )
        val batch = TransferBatchResult.from(listOf(result), listOf(source), listOf(option))
        assertEquals(setOf("peer-1"), batch.cancelledByReceiverDeviceIds)
        assertFalse(batch.allFailed)
        assertFalse(batch.partialFailure)
        assertNotEquals(AppI18n.t("send_failed_all_destinations"), batch.summaryMessage)
        assertEquals(AppI18n.t("send_cancelled_by_receiver"), batch.summaryMessage)
    }

    @Test
    fun aRealFailureStillCountsNextToACancel() {
        val result = MultiCopyResult(
            fileName = "a.bin",
            succeededDeviceIds = emptySet(),
            failures = mapOf("peer-1" to "Transfer cancelled", "peer-2" to "refused"),
            cancelledByReceiverDeviceIds = setOf("peer-1")
        )
        val option2 = MultiCopyDeviceOption("peer-2", "Mac", false, "192.0.2.11", 8080, "/x")
        val batch = TransferBatchResult.from(listOf(result), listOf(source), listOf(option, option2))
        assertTrue(batch.allFailed)
        assertEquals(setOf("peer-1"), batch.cancelledByReceiverDeviceIds)
    }

    @Test
    fun theRetryMergeKeepsTheCancel() {
        val first = MultiCopyResult("a.bin", emptySet(), mapOf("peer-1" to "x"), unreachableDeviceIds = setOf("peer-1"))
        val retry = MultiCopyResult("a.bin", emptySet(), mapOf("peer-1" to "Transfer cancelled"), cancelledByReceiverDeviceIds = setOf("peer-1"))
        val merged = first.mergedWith(retry)
        assertEquals(setOf("peer-1"), merged.cancelledByReceiverDeviceIds)
        assertTrue(merged.unreachableDeviceIds.isEmpty())
    }
}
