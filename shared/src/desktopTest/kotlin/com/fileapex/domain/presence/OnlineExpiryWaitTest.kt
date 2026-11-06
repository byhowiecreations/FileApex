package com.fileapex.domain.presence

import com.fileapex.data.db.PairedDeviceEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineExpiryWaitTest {
    private val now = 10_000_000L
    private fun device(id: String, lastSeen: Long) = PairedDeviceEntity(
        deviceId = id, deviceName = id, lastKnownIp = "10.0.0.2", port = 8080, publicKeyHash = "h", rootPath = "/", lastSeenEpochMs = lastSeen
    )

    @Test
    fun withNothingOnlineAndNoRouteThereIsNothingToWaitFor() {
        assertEquals(null, onlineExpiryWaitMs(emptyList(), emptySet(), emptyMap(), now))
    }

    @Test
    fun anOnlineDeviceIsWaitedForUntilItPassesThePresenceThreshold() {
        val seen = now - 5 * 60_000L
        val wait = onlineExpiryWaitMs(listOf(device("A", seen)), setOf("A"), emptyMap(), now)
        assertEquals(seen + LanPresenceTiming.PRESENCE_READY_THRESHOLD_MS - now + 1_000L, wait)
    }

    @Test
    fun aRouteLeaseEndingSoonerWinsOverTheOnlineExpiry() {
        val seen = now - 5 * 60_000L
        val wait = onlineExpiryWaitMs(listOf(device("A", seen)), setOf("A"), emptyMap(), now, routeExpiryWaitMs = 30_000L)
        assertEquals(30_000L, wait)
    }

    @Test
    fun aRouteLeaseAloneIsStillWaitedFor() {
        assertEquals(45_000L, onlineExpiryWaitMs(emptyList(), emptySet(), emptyMap(), now, routeExpiryWaitMs = 45_000L))
    }
}
