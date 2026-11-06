package com.fileapex.data.identity

import com.fileapex.data.db.seal.FieldSealer
import com.fileapex.data.seal.SealedText
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeviceNamePeerLabelsFileTest {
    private lateinit var dir: File
    private val sealer = FieldSealer(ByteArray(32) { it.toByte() })

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("peer-labels").toFile()
        peerLabelsDirectory = { dir }
        SealedText.sealerProvider = { sealer }
    }

    @After
    fun tearDown() {
        peerLabelsDirectory = null
        SealedText.sealerProvider = { null }
        dir.deleteRecursively()
    }

    @Test
    fun noFileReadsAsReadableAndEmpty() {
        val read = readPeerDeviceNameLabels()
        assertTrue(read.readable)
        assertTrue(read.labels.isEmpty())
    }

    @Test
    fun sealedLabelsReadBackWhenTheKeyIsThere() {
        persistPeerDeviceNameLabels(listOf(PeerDeviceNameLabel("a", "A", "Kitchen", 1)))
        val read = readPeerDeviceNameLabels()
        assertTrue(read.readable)
        assertEquals("Kitchen", read.labels.single().assignedName)
    }

    @Test
    fun sealedLabelsWithoutAKeyAreReportedLockedNotEmpty() {
        persistPeerDeviceNameLabels(listOf(PeerDeviceNameLabel("a", "A", "Kitchen", 1)))
        SealedText.sealerProvider = { null }
        val read = readPeerDeviceNameLabels()
        assertFalse(read.readable)
    }

    @Test
    fun aLockedFileIsNotOverwrittenWhenNoKeyIsAvailable() {
        persistPeerDeviceNameLabels(listOf(PeerDeviceNameLabel("a", "A", "Kitchen", 1)))
        val before = File(dir, "device_name_peer_labels.json").readText()
        SealedText.sealerProvider = { null }
        persistPeerDeviceNameLabels(listOf(PeerDeviceNameLabel("b", "B", "Office", 2)))
        assertEquals(before, File(dir, "device_name_peer_labels.json").readText())
    }
}
