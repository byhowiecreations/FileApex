package com.fileapex.friends

import com.fileapex.presentation.DeviceListRow
import com.fileapex.ui.devices.rowsWithFriendsLast
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendsStartupOrderTest {
    private fun row(id: String, friend: Boolean = false) = DeviceListRow(
        deviceId = id, deviceName = id, online = true, appVersion = null,
        platform = if (friend) "friend" else "android", friendGroup = if (friend) "g" else null
    )

    private val paired = listOf(row("p1"), row("p2"))
    private val friends = listOf(row("friend:f1", friend = true))

    @Test
    fun friendsAreHeldBackUntilThePairedRowsHaveLoaded() {
        assertEquals(emptyList<DeviceListRow>(), rowsWithFriendsLast(emptyList(), friends, pairedLoaded = false))
    }

    @Test
    fun onceLoadedPairedDevicesComeFirstAndFriendsFollow() {
        assertEquals(listOf("p1", "p2", "friend:f1"), rowsWithFriendsLast(paired, friends, pairedLoaded = true).map { it.deviceId })
    }

    @Test
    fun aLoadedRosterWithNoPairedDevicesStillShowsFriends() {
        assertEquals(listOf("friend:f1"), rowsWithFriendsLast(emptyList(), friends, pairedLoaded = true).map { it.deviceId })
    }

    @Test
    fun theFriendsEndpointWaitsForTheFirstPairedSnapshot() = runBlocking {
        val snapshot = MutableStateFlow(0L)
        val waiting = async { awaitPairedFirst(snapshot, graceMs = 10_000L) }
        repeat(5) { yield() }
        assertFalse(waiting.isCompleted)
        snapshot.value = 42L
        waiting.await()
        assertTrue(waiting.isCompleted)
    }

    @Test
    fun theFriendsEndpointStartsAnywayWhenNoSnapshotComes() = runBlocking {
        awaitPairedFirst(MutableStateFlow(0L), graceMs = 50L)
    }

    @Test
    fun noFriendsMeansTheEndpointIsNeverStarted() = runBlocking {
        var started = false
        startAfterPairedFirst(false, MutableStateFlow(42L)) { started = true }
        assertFalse(started)
    }

    @Test
    fun friendsStartTheEndpointOnlyAfterTheSnapshot() = runBlocking {
        val snapshot = MutableStateFlow(0L)
        var started = false
        val job = async { startAfterPairedFirst(true, snapshot) { started = true } }
        repeat(5) { yield() }
        assertFalse(started)
        snapshot.value = 1L
        job.await()
        assertTrue(started)
    }
}
