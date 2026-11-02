package com.fileapex.friends

import com.fileapex.platform.LinuxNotificationDelivery
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LinuxNotificationDeliveryTest {
    private open class ProcessStub(private val text: String) : Process() {
        override fun getInputStream(): java.io.InputStream = ByteArrayInputStream(text.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun destroy() = Unit
    }

    @Test fun clicksAndHostileTextUseArgumentsWithoutAShell() = runBlocking {
        val commands = CopyOnWriteArrayList<List<String>>()
        val finished = CompletableDeferred<Boolean>()
        val delivery = LinuxNotificationDelivery { args -> commands += args; ProcessStub("42\ndefault\n") }
        val title = "--option; $(not-a-command)"
        delivery.show(title, "a 'quoted' file") { finished.complete(it) }
        assertTrue(withTimeout(5_000L) { finished.await() })
        assertEquals("notify-send", commands.first().first())
        assertEquals(listOf("--", title, "a 'quoted' file"), commands.first().takeLast(3))
        assertTrue(commands.any { it.first() == "gdbus" && it.last() == "42" })
    }

    @Test fun dismissalKillsAWaitingHelperAndNeverOpensTheTarget() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Boolean>()
        val input = PipedInputStream()
        val writer = PipedOutputStream(input)
        var killed = false
        val commands = CopyOnWriteArrayList<List<String>>()
        val delivery = LinuxNotificationDelivery { args ->
            commands += args
            if (args.first() != "notify-send") ProcessStub("") else object : ProcessStub("") {
                override fun getInputStream() = input.also { started.complete(Unit) }
                override fun destroy() { killed = true; writer.close() }
            }
        }
        delivery.show("Alex", "file.txt") { finished.complete(it) }
        withTimeout(5_000L) { started.await() }
        delivery.close()
        assertFalse(withTimeout(5_000L) { finished.await() })
        assertTrue(killed)
        assertEquals(1, commands.size)
        input.close()
    }
}
