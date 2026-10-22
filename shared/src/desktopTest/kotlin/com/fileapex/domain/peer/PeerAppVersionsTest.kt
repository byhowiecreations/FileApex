package com.fileapex.domain.peer

import com.fileapex.presentation.DeviceListRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerAppVersionsTest {
    private fun state(id: String, client: String, app: String) =
        PeerNodeState(deviceId = id, deviceName = "n", port = 8080, clientVersion = client, appVersion = app)

    @Test
    fun onlyADockerNodeVersionIsRecorded() {
        PeerAppVersions.record(state("docker-1", "docker", "0.19.6a"))
        PeerAppVersions.record(state("phone-1", "0.19.5", "9.9.9"))
        PeerAppVersions.record(state("docker-2", "docker", ""))
        assertEquals("0.19.6a", PeerAppVersions.versions.value["docker-1"])
        assertTrue("phone-1" !in PeerAppVersions.versions.value)
        assertTrue("docker-2" !in PeerAppVersions.versions.value)
    }

    @Test
    fun theCardShowsTheDisplayVersionButKeepsTheClientVersionForLogic() {
        val row = DeviceListRow(deviceId = "d", deviceName = "Docker OMV", online = true, appVersion = "docker", displayVersion = "0.19.6a", lastSeenEpochMs = 0L)
        assertTrue(row.subtitle, row.subtitle.endsWith("v0.19.6a"))
        assertEquals("docker", row.appVersion)
        val plain = DeviceListRow(deviceId = "d", deviceName = "Docker OMV", online = true, appVersion = "docker", lastSeenEpochMs = 0L)
        assertTrue(plain.subtitle, plain.subtitle.endsWith("vdocker"))
    }
}
