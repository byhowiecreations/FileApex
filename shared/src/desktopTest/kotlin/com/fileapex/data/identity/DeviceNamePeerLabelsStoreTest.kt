package com.fileapex.data.identity

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeviceNamePeerLabelsStoreTest {
    private var reads = 0
    private var disk = listOf<PeerDeviceNameLabel>()
    private var readable = false
    private val writes = mutableListOf<List<PeerDeviceNameLabel>>()

    private fun label(id: String, name: String, at: Long) = PeerDeviceNameLabel(id, id, name, at)

    @Before
    fun setUp() {
        DeviceNamePeerLabelsStore.resetForTest(
            reader = { reads++; StoredPeerLabels(disk, readable) },
            writer = { writes += it; disk = it }
        )
    }

    @After
    fun tearDown() = DeviceNamePeerLabelsStore.resetForTest(
        reader = { StoredPeerLabels(emptyList(), true) },
        writer = {}
    )

    @Test
    fun aLockedReadIsRetriedOnEveryCallUntilTheLabelsOpen() {
        repeat(3) { DeviceNamePeerLabelsStore.ensureLoaded() }
        assertEquals(3, reads)
        readable = true
        DeviceNamePeerLabelsStore.ensureLoaded()
        DeviceNamePeerLabelsStore.ensureLoaded()
        assertEquals(4, reads)
    }

    @Test
    fun aRenameRecordedAfterTheKeyOpenedKeepsTheStoredLabels() {
        disk = listOf(label("a", "Kitchen", 10))
        DeviceNamePeerLabelsStore.ensureLoaded()
        readable = true
        DeviceNamePeerLabelsStore.record("b", "B", "Office", 20)
        assertEquals(setOf("a", "b"), disk.map { it.peerDeviceId }.toSet())
    }

    @Test
    fun aRenameRecordedWhileLockedIsShownButNeverWritten() {
        disk = listOf(label("a", "Kitchen", 10))
        DeviceNamePeerLabelsStore.record("b", "B", "Office", 20)
        assertTrue(writes.isEmpty())
        assertEquals(listOf("b"), DeviceNamePeerLabelsStore.labels.value.map { it.peerDeviceId })
    }

    @Test
    fun whenTheKeyOpensTheHeldRenameIsMergedWithTheStoredLabelsAndWritten() {
        disk = listOf(label("a", "Kitchen", 10), label("b", "Old", 5))
        DeviceNamePeerLabelsStore.record("b", "B", "Office", 20)
        readable = true
        DeviceNamePeerLabelsStore.ensureLoaded()
        val merged = DeviceNamePeerLabelsStore.labels.value.associate { it.peerDeviceId to it.assignedName }
        assertEquals(mapOf("a" to "Kitchen", "b" to "Office"), merged)
        assertEquals(merged, disk.associate { it.peerDeviceId to it.assignedName })
        assertEquals(1, writes.size)
    }

    @Test
    fun aStoredLabelNewerThanTheHeldOneWins() {
        disk = listOf(label("b", "Newer", 30))
        DeviceNamePeerLabelsStore.record("b", "B", "Older", 20)
        readable = true
        DeviceNamePeerLabelsStore.ensureLoaded()
        assertEquals("Newer", DeviceNamePeerLabelsStore.labels.value.single().assignedName)
    }

    @Test
    fun onceReadableTheLabelsAreKeptAndNotReadAgain() {
        readable = true
        repeat(4) { DeviceNamePeerLabelsStore.ensureLoaded() }
        assertEquals(1, reads)
    }
}
