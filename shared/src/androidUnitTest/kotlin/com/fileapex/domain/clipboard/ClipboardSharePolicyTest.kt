package com.fileapex.domain.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardSharePolicyTest {

    @Test
    fun unsetModeSendsToNobodyUntilUserPicks() {
        val targets = ClipboardSharePolicy.resolveTargetIds(
            mode = ClipboardShareMode.UNSET,
            pairedDeviceIds = listOf("a", "b"),
            selectedDeviceIds = setOf("a")
        )
        assertTrue(targets.isEmpty())
    }

    @Test
    fun allModeTargetsEveryPairedDevice() {
        val targets = ClipboardSharePolicy.resolveTargetIds(
            mode = ClipboardShareMode.ALL,
            pairedDeviceIds = listOf("a", "b", "c"),
            selectedDeviceIds = setOf("a")
        )
        assertEquals(setOf("a", "b", "c"), targets)
    }

    @Test
    fun specificModeKeepsOnlyCheckedDevices() {
        val targets = ClipboardSharePolicy.resolveTargetIds(
            mode = ClipboardShareMode.SPECIFIC,
            pairedDeviceIds = listOf("a", "b", "c"),
            selectedDeviceIds = setOf("b", "gone")
        )
        assertEquals(setOf("b"), targets)
    }

    @Test
    fun androidBroadcastKeepsOnlyDesktopPeers() {
        val peers = listOf(
            ClipboardSharePolicy.PeerRef("phone", isDesktop = false),
            ClipboardSharePolicy.PeerRef("mac", isDesktop = true),
            ClipboardSharePolicy.PeerRef("win", isDesktop = true)
        )
        val targets = ClipboardSharePolicy.resolveBroadcastTargets(
            mode = ClipboardShareMode.ALL,
            peers = peers,
            selectedDeviceIds = emptySet(),
            desktopPeersOnly = true
        )
        assertEquals(setOf("mac", "win"), targets)
    }

    @Test
    fun desktopBroadcastKeepsAllSelectedPeers() {
        val peers = listOf(
            ClipboardSharePolicy.PeerRef("phone", isDesktop = false),
            ClipboardSharePolicy.PeerRef("mac", isDesktop = true)
        )
        val targets = ClipboardSharePolicy.resolveBroadcastTargets(
            mode = ClipboardShareMode.ALL,
            peers = peers,
            selectedDeviceIds = emptySet(),
            desktopPeersOnly = false
        )
        assertEquals(setOf("phone", "mac"), targets)
    }

    @Test
    fun explicitDeviceIgnoresModeAndChecklist() {
        val targets = ClipboardSharePolicy.resolveTargetIds(
            mode = ClipboardShareMode.SPECIFIC,
            pairedDeviceIds = listOf("a", "b"),
            selectedDeviceIds = setOf("a"),
            explicitDeviceId = "b"
        )
        assertEquals(setOf("b"), targets)
    }

    @Test
    fun deviceIdSetSurvivesModeToggleEncoding() {
        val stored = ClipboardSharePolicy.encodeDeviceIdSet(setOf("phone", "tablet"))
        val parsed = ClipboardSharePolicy.parseDeviceIdSet(stored)
        assertEquals(setOf("phone", "tablet"), parsed)
        val stillThere = ClipboardSharePolicy.resolveTargetIds(
            mode = ClipboardShareMode.ALL,
            pairedDeviceIds = listOf("phone", "tablet", "desktop"),
            selectedDeviceIds = parsed
        )
        assertEquals(setOf("phone", "tablet", "desktop"), stillThere)
        val specific = ClipboardSharePolicy.resolveTargetIds(
            mode = ClipboardShareMode.SPECIFIC,
            pairedDeviceIds = listOf("phone", "tablet", "desktop"),
            selectedDeviceIds = parsed
        )
        assertEquals(setOf("phone", "tablet"), specific)
    }

    @Test
    fun ttlExpiresAtFifteenSeconds() {
        assertFalse(ClipboardSharePolicy.isExpired(1_000L, 1_000L + 14_999L))
        assertTrue(ClipboardSharePolicy.isExpired(1_000L, 1_000L + 15_000L))
    }

    @Test
    fun localLanRequiresWifiAndSameSubnet() {
        assertTrue(
            ClipboardSharePolicy.canUseLocalLan(
                lanConnected = true,
                peerHost = "192.168.1.40",
                localBindIps = listOf("192.168.1.12")
            )
        )
        assertTrue(
            ClipboardSharePolicy.canUseLocalLan(
                lanConnected = true,
                peerHost = "192.168.2.40",
                localBindIps = listOf("192.168.1.12")
            )
        )
        assertFalse(
            ClipboardSharePolicy.canUseLocalLan(
                lanConnected = false,
                peerHost = "192.168.1.40",
                localBindIps = listOf("192.168.1.12")
            )
        )
        assertFalse(
            ClipboardSharePolicy.canUseLocalLan(
                lanConnected = true,
                peerHost = "8.8.8.8",
                localBindIps = listOf("192.168.1.12")
            )
        )
    }

    @Test
    fun cellularFcmIsAndroidToAndroidOptIn() {
        assertTrue(
            ClipboardSharePolicy.canUseCellularFcm(
                viaCellularEnabled = true,
                selfIsAndroid = true,
                peerIsAndroid = true,
                googleLinked = true
            )
        )
        assertFalse(
            ClipboardSharePolicy.canUseCellularFcm(
                viaCellularEnabled = true,
                selfIsAndroid = true,
                peerIsAndroid = false,
                googleLinked = true
            )
        )
        assertFalse(
            ClipboardSharePolicy.canUseCellularFcm(
                viaCellularEnabled = false,
                selfIsAndroid = true,
                peerIsAndroid = true,
                googleLinked = true
            )
        )
    }
}
