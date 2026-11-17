package com.fileapex.domain.presence

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.network.FileApexClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredEndpointRefusalTest {
    private fun peer() = PairedDeviceEntity(
        deviceId = "P", deviceName = "P", lastKnownIp = "192.168.1.20", port = 8080, publicKeyHash = "", rootPath = "/data"
    )

    private class Host(private val answers: List<FileApexClient.HealthProbe>) : StoredEndpointHost {
        val log = mutableListOf<String>()
        private var next = 0
        override suspend fun probeHealth(host: String, port: Int, timeoutMs: Long, signal: com.fileapex.domain.link.HealthSignal?): FileApexClient.HealthProbe {
            log += "probe"
            return answers[minOf(next++, answers.size - 1)]
        }
        override suspend fun markRefused(deviceId: String) { log += "refused:$deviceId" }
        override suspend fun recordEndpointMismatch(peerId: String, host: String, answeredAs: String) { log += "mismatch:$answeredAs" }
        override suspend fun markReachable(deviceId: String) { log += "reach:$deviceId" }
        override fun onBulletinVersion(peer: PairedDeviceEntity, version: Long) = Unit
        override suspend fun touchLastSeenEpoch(deviceId: String) = Unit
        override suspend fun touchLastSeen(deviceId: String, host: String, port: Int) = Unit
        override suspend fun fetchNodeState(host: String, port: Int, timeoutMs: Long): PeerNodeState = error("none")
        override suspend fun applyNodeState(state: PeerNodeState, rosterDeviceId: String) = Unit
        override suspend fun supersedeHardwareTwins(state: PeerNodeState) = Unit
    }

    private val refused = FileApexClient.HealthProbe(reached = false, refused = true)
    private val timedOut = FileApexClient.HealthProbe(reached = false)

    private fun run(vararg answers: FileApexClient.HealthProbe, attempts: Int = 2): Pair<Boolean, List<String>> = runBlocking {
        val host = Host(answers.toList())
        val ok = StoredEndpointProbe(host, NodeStateClaims()).tryStoredEndpoint(peer(), attempts, 1L, 100L, fetchNodeState = false)
        ok to host.log
    }

    @Test
    fun everyAttemptRefusedMarksThePeerRefusedOnce() {
        val (ok, log) = run(refused, refused)
        assertFalse(ok)
        assertEquals(listOf("probe", "probe", "refused:P"), log)
    }

    @Test
    fun aTimeoutIsNotARefusal() {
        val (_, log) = run(timedOut, timedOut)
        assertFalse(log.any { it.startsWith("refused") })
    }

    @Test
    fun oneRefusalAmongTimeoutsIsNotEnough() {
        val (_, log) = run(refused, timedOut)
        assertFalse(log.any { it.startsWith("refused") })
    }

    @Test
    fun aRefusalFollowedByAnAnswerMarksReachableNotRefused() {
        val (ok, log) = run(refused, FileApexClient.HealthProbe(reached = true, respondedAs = "P"))
        assertTrue(ok)
        assertTrue(log.contains("reach:P"))
        assertFalse(log.any { it.startsWith("refused") })
    }

    @Test
    fun anAddressAnsweredByAnotherDeviceIsNotARefusal() {
        val (ok, log) = run(FileApexClient.HealthProbe(reached = true, respondedAs = "OTHER"))
        assertFalse(ok)
        assertFalse(log.any { it.startsWith("refused") })
    }
}
