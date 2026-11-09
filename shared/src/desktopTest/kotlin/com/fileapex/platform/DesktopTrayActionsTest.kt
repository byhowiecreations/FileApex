package com.fileapex.platform

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopTrayActionsTest {

    @Test
    fun aNoteIsTrimmedAndPosted() = runBlocking {
        val posted = mutableListOf<String>()
        val message = postTrayNote("  hello board \n") { posted += it }
        assertEquals(listOf("hello board"), posted)
        assertFalse(message.isError)
        assertTrue(message.text.isNotBlank())
    }

    @Test
    fun anEmptyNoteIsNeverPosted() = runBlocking {
        val posted = mutableListOf<String>()
        val message = postTrayNote("   \n ") { posted += it }
        assertTrue(posted.isEmpty())
        assertTrue(message.isError)
    }

    @Test
    fun aFailedPostReportsTheReason() = runBlocking {
        val message = postTrayNote("note") { error("board is not ready") }
        assertTrue(message.isError)
        assertEquals("board is not ready", message.text)
    }

    @Test
    fun anOpenDeviceRequestIsKeptUntilTheWindowTakesIt() {
        DesktopOpenDeviceRequest.consume()
        assertNull(DesktopOpenDeviceRequest.pendingDeviceId.value)
        DesktopOpenDeviceRequest.request("id-a")
        assertEquals("id-a", DesktopOpenDeviceRequest.pendingDeviceId.value)
        DesktopOpenDeviceRequest.request("id-b")
        assertEquals("id-b", DesktopOpenDeviceRequest.pendingDeviceId.value)
        DesktopOpenDeviceRequest.consume()
        assertNull(DesktopOpenDeviceRequest.pendingDeviceId.value)
    }

    @Test
    fun aBlankOrFriendRequestIsIgnored() {
        DesktopOpenDeviceRequest.consume()
        DesktopOpenDeviceRequest.request("  ")
        DesktopOpenDeviceRequest.request("friend:abc")
        assertNull(DesktopOpenDeviceRequest.pendingDeviceId.value)
    }
}
