package com.fileapex.domain.peer

import org.junit.Assert.assertEquals
import org.junit.Test

class PeerNodeStateStrangerTest {
    private val full = PeerNodeState(
        deviceId = "d1", deviceName = "Desk", ipAddress = "172.16.16.10", port = 8080,
        platform = "desktop", os = "macos", deviceMake = "Apple", deviceModel = "MacBook",
        rootPath = "/Users/me/Secret", publicKeyHash = "abc", publicKey = "key",
        membershipVersion = 42L, tlsPin = "pin", tlsPort = 8081
    )

    @Test
    fun aStrangerDoesNotLearnTheDeviceOrWhereItKeepsFiles() {
        val seen = PeerNodeStateMapper.forStranger(full)
        assertEquals("/", seen.rootPath)
        assertEquals("", seen.hardwareHash)
        assertEquals("", seen.ipAddress)
        assertEquals("", seen.lastKnownIp)
        assertEquals("", seen.platform)
        assertEquals("", seen.os)
        assertEquals("", seen.deviceMake)
        assertEquals("", seen.deviceModel)
    }

    @Test
    fun aStrangerStillGetsWhatPresenceAndPairingNeed() {
        val seen = PeerNodeStateMapper.forStranger(full)
        assertEquals("d1", seen.deviceId)
        assertEquals("Desk", seen.deviceName)
        assertEquals(8080, seen.port)
        assertEquals("abc", seen.publicKeyHash)
        assertEquals("key", seen.publicKey)
        assertEquals("pin", seen.tlsPin)
        assertEquals(8081, seen.tlsPort)
        assertEquals(42L, seen.membershipVersion)
    }

    @Test
    fun aReplyWithoutMakeOrModelKeepsTheChosenName() {
        val existing = com.fileapex.data.db.PairedDeviceEntity(
            deviceId = "d1", deviceName = "Kitchen", lastKnownIp = "172.16.16.10", port = 8080,
            publicKeyHash = "abc", rootPath = "/Users/me/Secret",
            platform = "desktop", os = "macos", deviceMake = "Apple", deviceModel = "MacBook"
        )
        val stranger = PeerNodeStateMapper.forStranger(full.copy(deviceName = "MacBook"))
        val row = PeerNodeStateMapper.toEntity(stranger, existing)
        assertEquals("Kitchen", row.deviceName)
        assertEquals("Apple", row.deviceMake)
        assertEquals("MacBook", row.deviceModel)
        assertEquals("172.16.16.10", row.lastKnownIp)
        assertEquals("desktop", row.platform)
    }
}
