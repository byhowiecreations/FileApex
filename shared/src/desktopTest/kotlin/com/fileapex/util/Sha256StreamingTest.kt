package com.fileapex.util

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Sha256StreamingTest {
    private fun temp(bytes: Int): File = File.createTempFile("sha-stream", ".bin").also {
        it.deleteOnExit()
        it.outputStream().use { out -> val chunk = ByteArray(8192) { i -> i.toByte() }; var left = bytes; while (left > 0) { out.write(chunk, 0, minOf(left, chunk.size)); left -= chunk.size } }
    }

    @Test
    fun `streaming hash matches the whole-file hash and reports full progress`() = runBlocking {
        val file = temp(300_000)
        var last = 0L
        val hash = sha256HexFileStreaming(file.absolutePath) { last = it }
        assertEquals(sha256HexFile(file.absolutePath), hash)
        assertEquals(file.length(), last)
    }

    @Test
    fun `cancelling stops the hash before the end of a large file`() = runBlocking {
        val file = temp(64 * 1024 * 1024)
        var seen = 0L
        val job = async { sha256HexFileStreaming(file.absolutePath) { seen = it } }
        while (seen == 0L) kotlinx.coroutines.yield()
        job.cancelAndJoin()
        assertTrue("hash ran to the end after cancel", seen < file.length())
        try { job.await(); fail("expected cancellation") } catch (_: CancellationException) {}
    }
}
