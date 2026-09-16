package com.fileapex.cli

import com.fileapex.cli.commands.CliCommandHandler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CliSendArgResolverTest {

    private val handler = CliCommandHandler()

    @Test
    fun testResolveSendArgsClipForward() = runBlocking {
        val (target, payload) = handler.resolveSendArgs("clip", "fold8", "/tmp")
        assertEquals("fold8", target)
        assertEquals("clip", payload)
    }

    @Test
    fun testResolveSendArgsClipReverse() = runBlocking {
        val (target, payload) = handler.resolveSendArgs("fold8", "clip", "/tmp")
        assertEquals("fold8", target)
        assertEquals("clip", payload)
    }

    @Test
    fun testResolveSendArgsClipboardAlias() = runBlocking {
        val (target, payload) = handler.resolveSendArgs("clipboard", "pixel9", "/tmp")
        assertEquals("pixel9", target)
        assertEquals("clip", payload)
    }

    @Test
    fun testResolveSendArgsBulletinBoard() = runBlocking {
        val (target, payload) = handler.resolveSendArgs("bb", "Meeting at 3", "/tmp")
        assertEquals("bb", target)
        assertEquals("Meeting at 3", payload)
    }

    @Test
    fun testResolveSendArgsBeep() = runBlocking {
        val (target, payload) = handler.resolveSendArgs("beep", "fold8", "/tmp")
        assertEquals("fold8", target)
        assertEquals("beep", payload)
    }
}
