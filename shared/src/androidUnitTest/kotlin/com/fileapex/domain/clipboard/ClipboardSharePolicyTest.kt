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

    @Test
    fun sendClipboardNotificationActionNeedsSharingAndOptIn() {
        assertFalse(
            ClipboardSharePolicy.showSendClipboardNotificationAction(
                sharingEnabled = true,
                sendClipboardNotificationEnabled = false
            )
        )
        assertFalse(
            ClipboardSharePolicy.showSendClipboardNotificationAction(
                sharingEnabled = false,
                sendClipboardNotificationEnabled = true
            )
        )
        assertTrue(
            ClipboardSharePolicy.showSendClipboardNotificationAction(
                sharingEnabled = true,
                sendClipboardNotificationEnabled = true
            )
        )
    }

    @Test
    fun androidFocusClipRetriesWaitPastResumeRace() {
        val retries = ClipboardSharePolicy.ANDROID_FOCUS_CLIP_RETRY_MS
        assertTrue(retries.size >= 3)
        assertEquals(350L, retries.first())
        assertTrue(retries.last() >= 1_200L)
        assertEquals(700L, ClipboardSharePolicy.ANDROID_FOREGROUND_CLIP_POLL_MS)
    }

    @Test
    fun calculateDeviceCountsCountsLocalAndRemoteDevices() {
        val phoneSelfPeers = listOf(ClipboardSharePolicy.PeerRef("mac-1", isDesktop = true))
        val counts1 = ClipboardSharePolicy.calculateDeviceCounts(selfIsAndroid = true, peers = phoneSelfPeers)
        assertEquals(1, counts1.totalPhones)
        assertEquals(1, counts1.totalDesktops)

        val desktopSelfPeers = listOf(ClipboardSharePolicy.PeerRef("pixel-1", isDesktop = false))
        val counts2 = ClipboardSharePolicy.calculateDeviceCounts(selfIsAndroid = false, peers = desktopSelfPeers)
        assertEquals(1, counts2.totalPhones)
        assertEquals(1, counts2.totalDesktops)

        val mixedPeers = listOf(
            ClipboardSharePolicy.PeerRef("mac-1", isDesktop = true),
            ClipboardSharePolicy.PeerRef("win-1", isDesktop = true),
            ClipboardSharePolicy.PeerRef("galaxy-1", isDesktop = false)
        )
        val counts3 = ClipboardSharePolicy.calculateDeviceCounts(selfIsAndroid = true, peers = mixedPeers)
        assertEquals(2, counts3.totalPhones)
        assertEquals(2, counts3.totalDesktops)
    }

    @Test
    fun resolveAutoDefaultTargetIdAutoDefaultsWhenExactlyOnePhoneAndOneDesktop() {
        val desktopPeer = ClipboardSharePolicy.PeerRef("mac-1", isDesktop = true)
        val autoTarget = ClipboardSharePolicy.resolveAutoDefaultTargetId(
            selfIsAndroid = true,
            peers = listOf(desktopPeer)
        )
        assertEquals("mac-1", autoTarget)

        val phonePeer = ClipboardSharePolicy.PeerRef("pixel-1", isDesktop = false)
        val autoTargetDesktop = ClipboardSharePolicy.resolveAutoDefaultTargetId(
            selfIsAndroid = false,
            peers = listOf(phonePeer)
        )
        assertEquals("pixel-1", autoTargetDesktop)
    }

    @Test
    fun resolveAutoDefaultTargetIdReturnsNullWhenMultiplePhonesOrDesktops() {
        val phonePeer = ClipboardSharePolicy.PeerRef("pixel-1", isDesktop = false)
        val autoTarget1 = ClipboardSharePolicy.resolveAutoDefaultTargetId(
            selfIsAndroid = true,
            peers = listOf(phonePeer)
        )
        org.junit.Assert.assertNull(autoTarget1)

        val twoDesktopPeers = listOf(
            ClipboardSharePolicy.PeerRef("mac-1", isDesktop = true),
            ClipboardSharePolicy.PeerRef("win-1", isDesktop = true)
        )
        val autoTarget2 = ClipboardSharePolicy.resolveAutoDefaultTargetId(
            selfIsAndroid = true,
            peers = twoDesktopPeers
        )
        org.junit.Assert.assertNull(autoTarget2)
    }

    @Test
    fun shouldPromptTargetConfigurationRespectsFlagAndDeviceCount() {
        val twoDesktopPeers = listOf(
            ClipboardSharePolicy.PeerRef("mac-1", isDesktop = true),
            ClipboardSharePolicy.PeerRef("win-1", isDesktop = true)
        )
        assertTrue(
            ClipboardSharePolicy.shouldPromptTargetConfiguration(
                isConfigured = false,
                selfIsAndroid = true,
                peers = twoDesktopPeers
            )
        )

        assertFalse(
            ClipboardSharePolicy.shouldPromptTargetConfiguration(
                isConfigured = true,
                selfIsAndroid = true,
                peers = twoDesktopPeers
            )
        )

        assertFalse(
            ClipboardSharePolicy.shouldPromptTargetConfiguration(
                isConfigured = false,
                selfIsAndroid = true,
                peers = listOf(ClipboardSharePolicy.PeerRef("mac-1", isDesktop = true))
            )
        )

        assertFalse(
            ClipboardSharePolicy.shouldPromptTargetConfiguration(
                isConfigured = false,
                selfIsAndroid = true,
                peers = listOf(ClipboardSharePolicy.PeerRef("pixel-1", isDesktop = false))
            )
        )
    }
}
