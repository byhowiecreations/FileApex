package com.fileapex.ui.transfer

import com.fileapex.domain.transfer.LiveTransferStats
import com.fileapex.domain.transfer.PendingTransferItem
import com.fileapex.domain.transfer.TransferHistoryEntry
import com.fileapex.friends.transfer.FriendTransfer
import com.fileapex.friends.transfer.FriendTransferDirection
import com.fileapex.friends.transfer.FriendTransferStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferRowMappersTest {
    private val t: Translate = { key, args -> if (args.isEmpty()) key else key + args.joinToString(",", "(", ")") }

    private fun live(
        active: Boolean = true, name: String = "a.mp4", device: String = "Mac", sent: Long = 50, total: Long = 100,
        progress: Float = 0.5f, speed: String = "3 MB/s", eta: String = "1 min", cancelable: Boolean = true
    ) = LiveTransferStats(
        isActive = active, currentFileName = name, destinationDeviceName = device, sentBytes = sent, totalBytes = total,
        progress = progress, speedFormatted = speed, etaFormatted = eta, cancelable = cancelable
    )

    private fun pending(sending: Boolean = false, paused: Boolean = false, error: String? = null) = PendingTransferItem(
        id = "q1", createdAtEpochMs = 0L, displayLabel = "Photos", pendingDeviceIds = listOf("d1", "d2"),
        pendingDeviceNames = listOf("Mac", "Fold"), sourceSummary = "", lastError = error, isSending = sending, isPaused = paused
    )

    private fun friend(status: FriendTransferStatus, direction: FriendTransferDirection = FriendTransferDirection.SEND, done: Long = 0, total: Long = 1000, speed: Long = 0, note: String = "") =
        FriendTransfer("f1", "k", "Ann", direction, "report.pdf", total, done, status, speed, note)

    @Test
    fun `live stats become one active row with percent speed and eta in that order`() {
        val m = TransferRowMappers.fromLive(live(), silentSeconds = 0, fallbackTitle = "sending", t = t)
        assertEquals("a.mp4", m.title)
        assertEquals("Mac", m.peer)
        assertEquals(TransferRowState.Active, m.state)
        assertEquals(0.5f, m.progress!!, 0.0001f)
        assertEquals("50% • 3 MB/s • 1 min", m.statusText)
        assertTrue(m.canCancel)
        assertNull(m.warning)
    }

    @Test
    fun `an unknown total is indeterminate and a blank name uses the fallback title`() {
        val m = TransferRowMappers.fromLive(live(total = 0, sent = 0, progress = 0f, name = ""), 0, "sending", t)
        assertNull(m.progress)
        assertEquals("sending", m.title)
        assertFalse(m.statusText.contains("%"))
    }

    @Test
    fun `percent is clamped and a silent stream gets a warning after 15 seconds`() {
        assertEquals(1f, TransferRowMappers.fromLive(live(progress = 1.4f), 0, "x", t).progress!!, 0.0001f)
        assertNull(TransferRowMappers.fromLive(live(), 14, "x", t).warning)
        assertEquals("transfer_no_data(15)", TransferRowMappers.fromLive(live(), 15, "x", t).warning)
        assertEquals(TransferRowState.Preparing, TransferRowMappers.fromLive(live(active = false), 0, "x", t).state)
    }

    @Test
    fun `a sending queue row shows the live numbers and can be cancelled`() {
        val m = TransferRowMappers.fromPending(pending(sending = true), live(progress = 0.4f), t)
        assertEquals(TransferRowState.Active, m.state)
        assertEquals(0.4f, m.progress!!, 0.0001f)
        assertEquals("sending • 40% • 3 MB/s • 1 min", m.statusText)
        assertTrue(m.canCancel); assertFalse(m.canRetry); assertTrue(m.canRemove)
        assertNull(TransferRowMappers.fromPending(pending(sending = true), live(active = false), t).progress)
    }

    @Test
    fun `paused failed and waiting queue rows differ in state text and actions`() {
        val paused = TransferRowMappers.fromPending(pending(paused = true), LiveTransferStats(), t)
        assertEquals(TransferRowState.Paused, paused.state); assertEquals("queue_paused", paused.statusText)
        assertTrue(paused.canRetry); assertFalse(paused.canCancel)
        val failed = TransferRowMappers.fromPending(pending(error = "no route"), LiveTransferStats(), t)
        assertEquals(TransferRowState.Failed, failed.state); assertEquals("no route", failed.error); assertTrue(failed.canRetry)
        val waiting = TransferRowMappers.fromPending(pending(), LiveTransferStats(), t)
        assertEquals(TransferRowState.Waiting, waiting.state)
        assertEquals("waiting_for_devices(Mac, Fold)", waiting.statusText)
        assertFalse(waiting.canRetry); assertTrue(waiting.canRemove)
    }

    @Test
    fun `friend transfers map every status and only unfinished ones can be cancelled`() {
        val expected = mapOf(
            FriendTransferStatus.QUEUED to TransferRowState.Queued, FriendTransferStatus.PREPARING to TransferRowState.Preparing,
            FriendTransferStatus.CONNECTING to TransferRowState.Connecting, FriendTransferStatus.WAITING to TransferRowState.Waiting,
            FriendTransferStatus.VERIFYING to TransferRowState.Verifying, FriendTransferStatus.ACTIVE to TransferRowState.Active,
            FriendTransferStatus.DONE to TransferRowState.Done, FriendTransferStatus.REFUSED to TransferRowState.Refused,
            FriendTransferStatus.FAILED to TransferRowState.Failed, FriendTransferStatus.CANCELED to TransferRowState.Canceled
        )
        for ((status, state) in expected) {
            val m = TransferRowMappers.fromFriend(friend(status), t)
            assertEquals(status.name, state, m.state)
            assertEquals(status.name, !status.finished, m.canCancel)
        }
    }

    @Test
    fun `a moving friend transfer shows bytes and speed and the other states show their own text`() {
        val moving = TransferRowMappers.fromFriend(friend(FriendTransferStatus.ACTIVE, done = 500, total = 1000, speed = 200), t)
        assertEquals(0.5f, moving.progress!!, 0.0001f)
        assertTrue(moving.statusText.startsWith("friends_progress("))
        assertTrue(moving.statusText.contains("friends_speed("))
        assertEquals("friends_status_sent", TransferRowMappers.fromFriend(friend(FriendTransferStatus.DONE), t).statusText)
        assertEquals("friends_status_received", TransferRowMappers.fromFriend(friend(FriendTransferStatus.DONE, FriendTransferDirection.RECEIVE), t).statusText)
        assertEquals("friends_refused_size", TransferRowMappers.fromFriend(friend(FriendTransferStatus.REFUSED, note = "size"), t).statusText)
        assertEquals("friends_failed_timeout", TransferRowMappers.fromFriend(friend(FriendTransferStatus.FAILED, note = "timeout"), t).statusText)
        assertNull(TransferRowMappers.fromFriend(friend(FriendTransferStatus.CONNECTING), t).progress)
    }

    @Test
    fun `a history entry is a finished row that can only be removed from view`() {
        val m = TransferRowMappers.fromHistory(TransferHistoryEntry("h1", "photo.jpg", "Mac", 2048L, 1_000L), t)
        assertEquals(TransferRowState.Done, m.state)
        assertEquals("photo.jpg", m.title); assertEquals("Mac", m.peer)
        assertFalse(m.canCancel); assertFalse(m.canRetry)
    }

    @Test
    fun `status filters group the eleven states`() {
        fun row(state: TransferRowState) = TransferRowModel("i", "t", "p", state, null, "", null, null, false, false, false, true)
        val all = TransferRowState.entries.map { row(it) }
        fun names(filter: TransferStatusFilter) = TransferStatusFilters.apply(all, filter).map { it.state }.toSet()
        assertEquals(TransferRowState.entries.toSet(), names(TransferStatusFilter.All))
        assertEquals(setOf(TransferRowState.Active, TransferRowState.Preparing, TransferRowState.Connecting, TransferRowState.Verifying), names(TransferStatusFilter.Active))
        assertEquals(setOf(TransferRowState.Queued, TransferRowState.Waiting, TransferRowState.Paused), names(TransferStatusFilter.Waiting))
        assertEquals(setOf(TransferRowState.Failed, TransferRowState.Refused, TransferRowState.Canceled), names(TransferStatusFilter.Failed))
        assertEquals(setOf(TransferRowState.Done), names(TransferStatusFilter.Done))
    }
}
