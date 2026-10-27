package com.fileapex.domain.presence

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.peer.PeerNodeState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MdnsPeerMatcherTest {
    private fun device(id: String, ip: String = "", hash: String = "") = PairedDeviceEntity(
        deviceId = id, deviceName = id, lastKnownIp = ip, port = 8080, publicKeyHash = hash, rootPath = "/data"
    )

    private class Roster(val rows: List<PairedDeviceEntity>, val state: PeerNodeState? = null) : MdnsRosterHost {
        val applied = mutableListOf<String>()
        override suspend fun device(deviceId: String) = rows.firstOrNull { it.deviceId == deviceId }
        override suspend fun devices() = rows
        override suspend fun fetchNodeState(host: String, port: Int, timeoutMs: Long): PeerNodeState = state ?: error("down")
        override suspend fun applyNodeState(state: PeerNodeState, rosterDeviceId: String) { applied += rosterDeviceId }
    }

    private fun state(id: String, hash: String = "") = PeerNodeState(deviceId = id, deviceName = id, port = 8080, publicKeyHash = hash)

    @Test
    fun hintedIdWins() = runBlocking {
        val roster = Roster(listOf(device("A"), device("B", ip = "10.0.0.5")))
        assertEquals("A", MdnsPeerMatcher(roster).resolve("10.0.0.5", 8080, " A ")?.deviceId)
    }

    @Test
    fun storedAddressMatchesWithoutAHint() = runBlocking {
        val roster = Roster(listOf(device("B", ip = "10.0.0.5")))
        assertEquals("B", MdnsPeerMatcher(roster).resolve("10.0.0.5", 8080, null)?.deviceId)
    }

    @Test
    fun blankAddressRowIsMatchedByTheKeyHashFromIdentity() = runBlocking {
        val roster = Roster(listOf(device("OLD", hash = "h")), state("NEW", hash = "h"))
        assertEquals("OLD", MdnsPeerMatcher(roster).resolve("10.0.0.9", 8080, null)?.deviceId)
        assertEquals(listOf("OLD"), roster.applied)
    }

    @Test
    fun unknownDeviceAndUnreachableHostGiveNothing() = runBlocking {
        assertNull(MdnsPeerMatcher(Roster(listOf(device("A")), state("Z", hash = "x"))).resolve("10.0.0.9", 8080, null))
        assertNull(MdnsPeerMatcher(Roster(listOf(device("A")))).resolve("10.0.0.9", 8080, null))
    }
}
