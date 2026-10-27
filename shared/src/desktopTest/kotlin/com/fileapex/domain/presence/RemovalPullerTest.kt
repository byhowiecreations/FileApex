package com.fileapex.domain.presence

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.pairing.ClusterSyncRequest
import com.fileapex.domain.pairing.PeerSyncEventKind
import com.fileapex.domain.pairing.RemovedDeviceRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemovalPullerTest {
    private val peer = PairedDeviceEntity(
        deviceId = "P", deviceName = "P", lastKnownIp = "10.0.0.2", port = 8080, publicKeyHash = "", rootPath = "/data"
    )
    private val endpoint = ResolvedPeerEndpoint("10.0.0.2", 8080, tailnet = false)

    private class Rig(var records: List<RemovedDeviceRecord>, var fail: Boolean = false) {
        var fetches = 0
        val merged = mutableListOf<Pair<ClusterSyncRequest, String>>()
        val selfRecords = mutableListOf<RemovedDeviceRecord>()
        val puller = RemovalPuller(
            ourFingerprint = { "ours" },
            localDeviceId = { "ME" },
            fetchRemoved = { _, _ ->
                fetches++
                if (fail) error("down")
                records
            },
            merge = { request, sender -> merged += request to sender },
            onSelfRecord = { _, record -> selfRecords += record }
        )
    }

    @Test
    fun readsOnceNeverMergesARecordNamingThisDeviceButPassesItOn() = runBlocking {
        val rig = Rig(listOf(RemovedDeviceRecord("X", removedAt = 7L), RemovedDeviceRecord("ME", removedAt = 9L)))
        rig.puller.pull(peer, endpoint, "theirs")
        rig.puller.pull(peer, endpoint, "theirs")
        assertEquals(1, rig.fetches)
        val (request, sender) = rig.merged.single()
        assertEquals("P", sender)
        assertEquals(PeerSyncEventKind.REMOVAL, request.eventKind)
        assertEquals(listOf("X"), request.removedDevices.map { it.deviceId })
        assertEquals(7L, request.clusterVersion)
        assertEquals(listOf("ME"), rig.selfRecords.map { it.deviceId })
    }

    @Test
    fun sameFingerprintAsOursReadsNothing() = runBlocking {
        val rig = Rig(listOf(RemovedDeviceRecord("X")))
        rig.puller.pull(peer, endpoint, "ours")
        assertEquals(0, rig.fetches)
    }

    @Test
    fun aFailedReadIsTriedAgainOnTheNextFingerprintMatch() = runBlocking {
        val rig = Rig(listOf(RemovedDeviceRecord("X")), fail = true)
        rig.puller.pull(peer, endpoint, "theirs")
        rig.fail = false
        rig.puller.pull(peer, endpoint, "theirs")
        assertEquals(2, rig.fetches)
        assertTrue(rig.merged.isNotEmpty())
    }
}
