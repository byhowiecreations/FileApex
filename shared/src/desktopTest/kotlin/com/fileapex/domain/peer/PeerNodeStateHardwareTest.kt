package com.fileapex.domain.peer

import com.fileapex.data.db.PairedDeviceEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class PeerNodeStateHardwareTest {
    private fun state(hash: String) = PeerNodeState(
        deviceId = "d1", deviceName = "Desk", port = 8080, rootPath = "/Users/me", hardwareHash = hash
    )

    @Test
    fun aStrangerNeverSeesTheHardwareHash() {
        assertEquals("", PeerNodeStateMapper.forStranger(state("aaa")).hardwareHash)
    }

    @Test
    fun theHashIsStoredOnTheRowWhenAPairedPeerSendsIt() {
        assertEquals("aaa", PeerNodeStateMapper.toEntity(state("aaa")).hardwareHash)
    }

    @Test
    fun aReplyWithoutAHashDoesNotEraseTheStoredOne() {
        val existing = PairedDeviceEntity(
            deviceId = "d1", deviceName = "Desk", lastKnownIp = "192.168.1.5", port = 8080,
            publicKeyHash = "", rootPath = "/", hardwareHash = "aaa"
        )
        assertEquals("aaa", PeerNodeStateMapper.toEntity(state(""), existing).hardwareHash)
    }
}
