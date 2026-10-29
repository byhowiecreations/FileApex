package com.fileapex.platform

import com.fileapex.network.LocalShareDevice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopLocalExtensionApiTest {
    private class Rig(
        var queued: SendOutcome = SendOutcome(sent = true, queued = false, message = "Sent 2 files")
    ) {
        val sent = mutableListOf<Pair<List<String>, List<String>>>()
        val files = mutableListOf<Triple<String, String, String>>()
        val texts = mutableListOf<String>()
        var failWith: Throwable? = null
        val api = DesktopLocalExtensionApi(
            devices = { listOf(ShareRow("d1", "Fold", false, true), ShareRow("d2", "Studio", false, false), ShareRow("d3", "Gone", true, true)) },
            sendOrQueue = { paths, ids -> failWith?.let { throw it }; sent += paths to ids; queued },
            postFile = { path, caption -> files += Triple(path, java.io.File(path).name, caption) },
            postText = { texts += it },
            text = { key -> key }
        )
    }

    @Test
    fun onlyLiveDevicesAreListedAndOnlineOnesAreReady() = runBlocking {
        assertEquals(
            listOf(LocalShareDevice("d1", "Fold", true), LocalShareDevice("d2", "Studio", false)),
            Rig().api.devices()
        )
    }

    @Test
    fun aSentFileIsOkWithTheQueueMessage() = runBlocking {
        val rig = Rig()
        val result = rig.api.send(listOf("/a/one.jpg"), listOf("d1"))
        assertTrue(result.ok)
        assertEquals("Sent 2 files", result.message)
        assertEquals(listOf(listOf("/a/one.jpg") to listOf("d1")), rig.sent)
    }

    @Test
    fun aQueuedSendCountsAsOk() = runBlocking {
        val rig = Rig(SendOutcome(sent = false, queued = true, message = "Queued"))
        assertTrue(rig.api.send(listOf("/a"), listOf("d2")).ok)
    }

    @Test
    fun aSendThatNeitherWentNorQueuedFails() = runBlocking {
        val rig = Rig(SendOutcome(sent = false, queued = false, message = "Could not send"))
        val result = rig.api.send(listOf("/a"), listOf("d2"))
        assertFalse(result.ok)
        assertEquals("Could not send", result.message)
    }

    @Test
    fun aThrownErrorIsAFailureWithItsMessage() = runBlocking {
        val rig = Rig().also { it.failWith = IllegalStateException("not ready") }
        val result = rig.api.send(listOf("/a"), listOf("d1"))
        assertFalse(result.ok)
        assertEquals("not ready", result.message)
    }

    @Test
    fun aBulletinFilePostUsesTheFirstFileAndTheTextAsCaption() = runBlocking {
        val tmp = java.io.File.createTempFile("bulletin", ".txt").also { it.writeText("x") }
        val rig = Rig()
        val result = rig.api.postBulletin("caption", listOf(tmp.absolutePath, "/ignored"))
        assertTrue(result.ok)
        assertEquals(listOf(Triple(tmp.absolutePath, tmp.name, "caption")), rig.files)
        assertTrue(rig.texts.isEmpty())
        tmp.delete()
        Unit
    }

    @Test
    fun aBulletinPostWithOnlyTextPostsTheText() = runBlocking {
        val rig = Rig()
        assertTrue(rig.api.postBulletin("hello", emptyList()).ok)
        assertEquals(listOf("hello"), rig.texts)
        assertTrue(rig.files.isEmpty())
    }
}
