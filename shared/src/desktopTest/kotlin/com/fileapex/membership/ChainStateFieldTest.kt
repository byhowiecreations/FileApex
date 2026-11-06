package com.fileapex.membership

import com.fileapex.domain.peer.PeerNodeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainStateFieldTest {
    private val head = "a".repeat(64)

    @Test
    fun theFieldKeepsTheFirst128BitsOfTheHeadAndRoundTrips() {
        val field = ChainStateField.of(seq = 12, head = head, profile = "0a1b2c3d")
        assertEquals("v1.12.${"a".repeat(32)}.0a1b2c3d", field.encode())
        assertEquals(field, ChainStateField.parse(field.encode()))
        assertTrue(field.encode().length <= ChainStateField.MAX_LENGTH)
    }

    @Test
    fun aDeviceWithNoChainSendsPositionZeroAndNoHead() {
        val field = ChainStateField.of(seq = 0, head = "", profile = "00000000")
        assertEquals("v1.0..00000000", field.encode())
        assertEquals(field, ChainStateField.parse("v1.0..00000000"))
    }

    @Test
    fun anythingMalformedIsIgnored() {
        val ok = "v1.5.${"b".repeat(32)}.deadbeef"
        assertTrue(ChainStateField.parse(ok) != null)
        listOf(
            null, "", "garbage", "v2.5.${"b".repeat(32)}.deadbeef", "v1.-5.${"b".repeat(32)}.deadbeef",
            "v1.5.${"b".repeat(31)}.deadbeef", "v1.5.${"B".repeat(32)}.deadbeef", "v1.5.${"b".repeat(32)}.deadbee",
            "v1.99999999999.${"b".repeat(32)}.deadbeef", "v1.0.${"b".repeat(32)}.deadbeef", "v1.5..deadbeef",
            ok + ".extra", ok + "x".repeat(200), " $ok", "v1.5.${"b".repeat(32)}.zzzzzzzz"
        ).forEach { assertNull("accepted: $it", ChainStateField.parse(it)) }
    }

    @Test
    fun theVectorTheGoDaemonParsesParsesHereToo() {
        val field = ChainStateField.parse("v1.12." + "a".repeat(32) + ".0a1b2c3d")
        assertEquals(12L, field?.seq)
        assertEquals("0a1b2c3d", field?.profile)
    }

    @Test
    fun theProfileVersionIsStableAndFollowsTheDigest() {
        val a = ChainStateField.of(3, head, "00000001")
        val b = ChainStateField.of(3, head, "00000002")
        assertEquals(1L, a.profileVersion)
        assertEquals(2L, b.profileVersion)
    }

    private fun state() = PeerNodeState(
        deviceId = "d", deviceName = "Fold8", ipAddress = "10.0.0.130", lastKnownIp = "10.0.0.130", port = 8080,
        clientVersion = "0.20.5", clientVersionCode = 232, platform = "android", os = "android", deviceMake = "samsung",
        deviceModel = "SM-F971U1", publicKey = "pk", publicKeyHash = "pkh", tlsPin = "1".repeat(64), tlsPort = 8443
    )

    @Test
    fun theProfileDigestIgnoresAddressesPresenceAndRelayedFields() {
        val base = ProfileDigest.of(state())
        assertEquals(8, base.length)
        assertEquals(base, ProfileDigest.of(state().copy(ipAddress = "10.0.0.9", lastKnownIp = "10.0.0.9")))
        assertEquals(base, ProfileDigest.of(state().copy(lastSeenTimestamp = 99L)))
        assertEquals(base, ProfileDigest.of(state().copy(membershipVersion = 7L, clusterVersion = 3L, isRemoved = false)))
    }

    @Test
    fun theProfileDigestChangesForEveryStableField() {
        val base = ProfileDigest.of(state())
        listOf(
            state().copy(deviceName = "Renamed"), state().copy(port = 8090), state().copy(clientVersion = "0.20.6"),
            state().copy(clientVersionCode = 233), state().copy(platform = "x"), state().copy(os = "macos"),
            state().copy(deviceMake = "m"), state().copy(deviceModel = "n"), state().copy(publicKey = "other"),
            state().copy(publicKeyHash = "other"), state().copy(tlsPin = "2".repeat(64)), state().copy(tlsPort = 9443),
            state().copy(rootPath = "/x"), state().copy(pinRequired = true), state().copy(supportedProtocols = listOf("x"))
        ).forEach { assertNotEquals("unchanged digest for $it", base, ProfileDigest.of(it)) }
    }
}
