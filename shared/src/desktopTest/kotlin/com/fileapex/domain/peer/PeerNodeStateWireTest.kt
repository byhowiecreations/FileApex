package com.fileapex.domain.peer

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerNodeStateWireTest {
    private val wire = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun emptyLegacyFieldsAreNotSent() {
        val text = wire.encodeToString(PeerNodeState.serializer(), PeerNodeState(deviceId = "a", deviceName = "A", port = 1))
        assertFalse(text.contains("lastKnownIp"))
        assertFalse(text.contains("appVersion\""))
        assertFalse(text.contains("appVersionCode"))
        assertTrue(text.contains("clientVersion"))
    }

    @Test
    fun filledLegacyFieldsAreStillSent() {
        val state = PeerNodeState(deviceId = "a", deviceName = "A", port = 1, lastKnownIp = "10.0.0.2", appVersion = "0.9", appVersionCode = 9)
        val text = wire.encodeToString(PeerNodeState.serializer(), state)
        assertTrue(text.contains("\"lastKnownIp\":\"10.0.0.2\""))
        assertTrue(text.contains("\"appVersionCode\":9"))
    }

    @Test
    fun olderPayloadsStillDecode() {
        val old = """{"deviceId":"a","deviceName":"A","ipAddress":"10.0.0.5","lastKnownIp":"","port":8080,"appVersion":"","appVersionCode":0}"""
        val state = wire.decodeFromString(PeerNodeState.serializer(), old)
        assertEquals("10.0.0.5", state.resolvedIpAddress)
        assertEquals(8080, state.port)
    }
}
