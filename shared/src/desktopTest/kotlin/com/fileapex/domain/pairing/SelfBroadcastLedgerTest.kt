package com.fileapex.domain.pairing

import com.fileapex.data.db.PairedDeviceEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class SelfBroadcastLedgerTest {
    private val hour = 60 * 60 * 1000L
    private fun peer(id: String) = PairedDeviceEntity(
        deviceId = id, deviceName = id, lastKnownIp = "172.16.16.10", port = 8080, publicKeyHash = "",
        rootPath = "/", clientVersion = "0.19.1", clientVersionCode = 207
    )

    @Test
    fun everyPeerIsPendingTheFirstTime() {
        val ledger = SelfBroadcastLedger()
        assertEquals(listOf("a", "b"), ledger.pending(listOf(peer("a"), peer("b")), fingerprint = 1, nowMs = 0L).map { it.deviceId })
    }

    @Test
    fun aPeerThatGotTheCurrentStateIsNotSentItAgain() {
        val ledger = SelfBroadcastLedger()
        ledger.markDelivered("a", fingerprint = 1, nowMs = 0L)
        assertEquals(listOf("b"), ledger.pending(listOf(peer("a"), peer("b")), fingerprint = 1, nowMs = hour).map { it.deviceId })
    }

    @Test
    fun aChangedStateIsSentToEveryoneAgain() {
        val ledger = SelfBroadcastLedger()
        ledger.markDelivered("a", fingerprint = 1, nowMs = 0L)
        assertEquals(listOf("a"), ledger.pending(listOf(peer("a")), fingerprint = 2, nowMs = hour).map { it.deviceId })
    }

    @Test
    fun anUnchangedStateIsResentAfterTheSafetyWindow() {
        val ledger = SelfBroadcastLedger(safetyMs = 6 * hour)
        ledger.markDelivered("a", fingerprint = 1, nowMs = 0L)
        assertEquals(emptyList<String>(), ledger.pending(listOf(peer("a")), fingerprint = 1, nowMs = 5 * hour).map { it.deviceId })
        assertEquals(listOf("a"), ledger.pending(listOf(peer("a")), fingerprint = 1, nowMs = 7 * hour).map { it.deviceId })
    }

    @Test
    fun aFailedDeliveryStaysPending() {
        val ledger = SelfBroadcastLedger()
        // nothing marked delivered
        assertEquals(listOf("a"), ledger.pending(listOf(peer("a")), fingerprint = 1, nowMs = 0L).map { it.deviceId })
    }
}
