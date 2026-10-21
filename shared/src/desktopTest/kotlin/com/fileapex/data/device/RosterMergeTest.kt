package com.fileapex.data.device

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.identity.LocalIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure roster rules, tested directly: same-device detection, winner choice, endpoint rules and row normalizing. */
class RosterMergeTest {
    private fun device(
        id: String,
        name: String = "Phone",
        ip: String = "192.168.1.20",
        port: Int = 8080,
        hash: String = "",
        seen: Long = 0L,
        version: String = ""
    ) = PairedDeviceEntity(
        deviceId = id, deviceName = name, lastKnownIp = ip, port = port, publicKeyHash = hash,
        rootPath = "/", lastSeenEpochMs = seen, clientVersion = version
    )

    @Test
    fun endpointKeyRejectsBlankLoopbackAndWildcard() {
        assertNull(RosterMerge.endpointKey("", 80))
        assertNull(RosterMerge.endpointKey("  ", 80))
        assertNull(RosterMerge.endpointKey("127.0.0.1", 80))
        assertNull(RosterMerge.endpointKey("0.0.0.0", 80))
        assertEquals("192.168.1.5:8080", RosterMerge.endpointKey(" 192.168.1.5 ", 8080))
    }

    @Test
    fun sameIdOrSamePublicKeyHashAreAliases() {
        assertTrue(RosterMerge.areAliases(device("a"), device("a", name = "Other", ip = "10.0.0.9")))
        assertTrue(RosterMerge.areAliases(device("a", hash = "h1", ip = "10.0.0.1"), device("b", hash = "h1", ip = "10.0.0.2")))
        assertFalse(RosterMerge.areAliases(device("a", name = "One", ip = "10.0.0.1"), device("b", name = "Two", ip = "10.0.0.2")))
    }

    @Test
    fun nameTwinsWithoutAnEndpointAreAliasesButWithEndpointsAreNot() {
        assertTrue(RosterMerge.areAliases(device("a", name = "Honor", ip = ""), device("b", name = " honor ", ip = "10.0.0.2")))
        assertFalse(RosterMerge.areAliases(device("a", name = "Honor", ip = "10.0.0.1"), device("b", name = "Honor", ip = "10.0.0.2")))
    }

    @Test
    fun twoDifferentSecurityKeysAreNeverTheSameDeviceEvenWithTheSameNameAndNoAddress() {
        assertFalse(RosterMerge.areAliases(device("a", name = "Pixel", ip = "", hash = "h1"), device("b", name = "Pixel", ip = "", hash = "h2")))
        assertEquals(2, RosterMerge.collapseAliases(listOf(device("a", name = "Pixel", ip = "", hash = "h1"), device("b", name = "Pixel", ip = "", hash = "h2"))).size)
        assertTrue(RosterMerge.areAliases(device("a", name = "Pixel", ip = "", hash = "h1"), device("b", name = "Pixel", ip = "", hash = "")))
    }

    @Test
    fun aRowWithAnEndpointOutranksOneWithoutAndNewerSeenWins() {
        val noEndpoint = device("a", ip = "", seen = 900)
        val withEndpoint = device("b", seen = 100)
        assertEquals("b", RosterMerge.pickWinner(listOf(noEndpoint, withEndpoint)).deviceId)
        val older = device("c", seen = 100)
        val newer = device("d", seen = 200)
        assertEquals("d", RosterMerge.pickWinner(listOf(older, newer)).deviceId)
    }

    @Test
    fun endpointSourceIsTheFresherUsableOneAndAZeroStampNeverBeatsALiveProbe() {
        val live = device("a", ip = "10.0.0.1", seen = 500)
        val cloud = device("a", ip = "10.0.0.2", seen = 0)
        assertEquals("10.0.0.1", RosterMerge.pickEndpointSource(live, cloud).lastKnownIp)
        assertEquals("10.0.0.1", RosterMerge.pickEndpointSource(cloud, live).lastKnownIp)
        val blank = device("a", ip = "")
        assertEquals("10.0.0.1", RosterMerge.pickEndpointSource(blank, live).lastKnownIp)
        assertEquals("10.0.0.2", RosterMerge.pickEndpointSource(null, cloud).lastKnownIp)
    }

    @Test
    fun collapseKeepsOneRowPerDeviceAndSortsByName() {
        val rows = listOf(
            device("b", name = "Zed", ip = "10.0.0.2", hash = "hz"),
            device("a", name = "Alpha", ip = "10.0.0.1", hash = "ha"),
            device("a2", name = "Alpha", ip = "", hash = "ha")
        )
        val collapsed = RosterMerge.collapseAliases(rows)
        assertEquals(listOf("Alpha", "Zed"), collapsed.map { it.deviceName })
        assertEquals(2, collapsed.size)
    }

    @Test
    fun theLocalDeviceIsRecognisedByIdSentinelOrEndpoint() {
        val local = LocalDeviceRef(deviceId = "me", endpoints = setOf("192.168.1.9:8080"))
        assertTrue(RosterMerge.isLocalDevice(device("me"), local))
        assertTrue(RosterMerge.isLocalDevice(device(LocalIdentity.LOCAL_DEVICE_ID), local))
        assertTrue(RosterMerge.isLocalDevice(device("x", ip = "192.168.1.9", port = 8080), local))
        assertFalse(RosterMerge.isLocalDevice(device("x", ip = "192.168.1.10", port = 8080), local))
        assertFalse(RosterMerge.isLocalDevice(device("me2", ip = ""), LocalDeviceRef.None))
    }

    @Test
    fun aPeersLayoutNeverReplacesWhereThisDevicePlacedACard() {
        val mine = device("a").copy(cardPosX = 10f, cardPosY = 20f, cardSortOrder = 3, cardMenuOrder = "1,2", tilePosX = 5f, tilePosY = 6f, tileSortOrder = 2, tileMenuOrder = "x")
        val theirs = device("a").copy(cardPosX = 99f, cardPosY = 98f, cardSortOrder = 7, cardMenuOrder = "9", tilePosX = 77f, tilePosY = 76f, tileSortOrder = 8, tileMenuOrder = "y")
        val merged = RosterMerge.normalize(theirs, preserveFrom = mine)
        assertEquals(10f, merged.cardPosX)
        assertEquals(20f, merged.cardPosY)
        assertEquals(3, merged.cardSortOrder)
        assertEquals("1,2", merged.cardMenuOrder)
        assertEquals(5f, merged.tilePosX)
        assertEquals(6f, merged.tilePosY)
        assertEquals(2, merged.tileSortOrder)
        assertEquals("x", merged.tileMenuOrder)
    }

    @Test
    fun aPeersLayoutSeedsACardThisDeviceHasNotPlacedYet() {
        val theirs = device("a").copy(cardPosX = 99f, cardPosY = 98f, tileSortOrder = 8)
        val merged = RosterMerge.normalize(theirs, preserveFrom = device("a"))
        assertEquals(99f, merged.cardPosX)
        assertEquals(98f, merged.cardPosY)
        assertEquals(8, merged.tileSortOrder)
    }

    @Test
    fun normalizeTrimsAndKeepsWhatTheIncomingRowLacks() {
        val old = device("a", name = "Old", ip = "10.0.0.1", port = 9000, version = "0.19.0").copy(rootPath = "/storage/emulated/0", platform = "Android")
        val incoming = device("a", name = " New ", ip = "", port = 0).copy(rootPath = "/", platform = "")
        val merged = RosterMerge.normalize(incoming, preserveFrom = old)
        assertEquals("New", merged.deviceName)
        assertEquals("10.0.0.1", merged.lastKnownIp)
        assertEquals(9000, merged.port)
        assertEquals("/storage/emulated/0", merged.rootPath)
        assertEquals("0.19.0", merged.clientVersion)
        assertEquals("Android", merged.platform)
    }

    @Test
    fun normalizeDefaultsTheRootByPlatform() {
        assertEquals("/storage/emulated/0", RosterMerge.normalize(device("a").copy(platform = "android")).rootPath)
        assertEquals("/", RosterMerge.normalize(device("a").copy(platform = "macOS")).rootPath)
    }

    @Test
    fun smallHelpersPickTheFirstMeaningfulValue() {
        assertEquals("b", RosterMerge.firstNonBlank(" ", "b", "c"))
        assertEquals("", RosterMerge.firstNonBlank(" ", ""))
        assertEquals(3, RosterMerge.firstPositive(0, -1, 3, 4))
        assertEquals(0, RosterMerge.firstPositive(0, -1))
        assertEquals("abc", RosterMerge.normalizeName("  AbC "))
    }
}
