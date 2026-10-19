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
    fun aStrangerDoesNotLearnWhereTheSharedFolderIs() {
        val seen = PeerNodeStateMapper.forStranger(full)
        assertEquals("/", seen.rootPath)
    }

    @Test
    fun aStrangerStillGetsWhatPresenceAndPairingNeed() {
        val seen = PeerNodeStateMapper.forStranger(full)
        assertEquals(full.copy(rootPath = "/"), seen)
        assertEquals("d1", seen.deviceId)
        assertEquals("pin", seen.tlsPin)
        assertEquals(42L, seen.membershipVersion)
    }
}
