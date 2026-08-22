package com.fileapex.network

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferResumeProtocolTest {

    @Test
    fun parseByteOffsetPrefersQueryThenRange() {
        assertEquals(0L, TransferResumeProtocol.parseByteOffset(null, null))
        assertEquals(4096L, TransferResumeProtocol.parseByteOffset("4096", "bytes=1-"))
        assertEquals(100L, TransferResumeProtocol.parseByteOffset(null, "bytes=100-199"))
        assertEquals(0L, TransferResumeProtocol.parseByteOffset("-12", "bytes=-500"))
    }

    @Test
    fun parseContentRangeAndTotal() {
        assertEquals(2048L, TransferResumeProtocol.parseContentRangeStart("bytes 2048-4095/8000"))
        assertEquals(8000L, TransferResumeProtocol.parseContentRangeTotal("bytes 2048-4095/8000"))
        assertEquals(
            5000L,
            TransferResumeProtocol.parseTotalSize(
                queryTotal = "5000",
                contentRange = "bytes 10-20/99",
                sessionLength = 20L,
                offset = 10L
            )
        )
        assertEquals(
            30L,
            TransferResumeProtocol.parseTotalSize(
                queryTotal = null,
                contentRange = null,
                sessionLength = 20L,
                offset = 10L
            )
        )
    }

    @Test
    fun inspectIncomingReportsPartLengthAndFinalizesWhenComplete() {
        val dir = File.createTempFile("fileapex-resume-", ".dir")
        check(dir.delete() && dir.mkdirs())
        try {
            val preferred = File(dir, "vacation.jpg").absolutePath
            val part = File(SocketFileStreamer.partPathFor(preferred))
            part.writeBytes(ByteArray(1500) { 7 })
            val mid = TransferResumeProtocol.inspectIncoming(preferred, expectedSize = 4000L)
            assertEquals(1500L, mid.offset)
            assertFalse(mid.complete)
            assertTrue(part.exists())

            part.writeBytes(ByteArray(4000) { 8 })
            val done = TransferResumeProtocol.inspectIncoming(preferred, expectedSize = 4000L)
            assertEquals(4000L, done.offset)
            assertTrue(done.complete)
            assertFalse(part.exists())
            assertTrue(File(preferred).isFile)
            assertEquals(4000L, File(preferred).length())
        } finally {
            dir.deleteRecursively()
        }
    }
}

class SocketFileStreamerTest {

    @Test
    fun streamFromOffsetDoesNotRereadPrefix() {
        val file = File.createTempFile("fileapex-stream-", ".bin")
        try {
            val payload = ByteArray(10_000) { index -> (index % 251).toByte() }
            file.writeBytes(payload)
            val sink = ArrayList<Byte>()
            val sent = SocketFileStreamer.streamFromOffset(
                sourcePath = file.absolutePath,
                offset = 2500L,
                byteLimit = 500L
            ) { buffer, length ->
                repeat(length) { sink.add(buffer[it]) }
            }
            assertEquals(500L, sent)
            assertEquals(500, sink.size)
            assertArrayEquals(payload.copyOfRange(2500, 3000), sink.toByteArray())
        } finally {
            file.delete()
        }
    }

    @Test
    fun appenderResumesAtDiskLength() {
        val file = File.createTempFile("fileapex-append-", ".bin")
        try {
            SocketFileStreamer.openAppender(file.absolutePath, 0L).use { raf ->
                raf.write(ByteArray(300) { 1 })
            }
            assertEquals(300L, SocketFileStreamer.fileLength(file.absolutePath))
            SocketFileStreamer.openAppender(file.absolutePath, 300L).use { raf ->
                raf.write(ByteArray(200) { 2 })
            }
            val bytes = file.readBytes()
            assertEquals(500, bytes.size)
            assertTrue(bytes.take(300).all { it == 1.toByte() })
            assertTrue(bytes.drop(300).all { it == 2.toByte() })
        } finally {
            file.delete()
        }
    }

    @Test
    fun bufferSizeIs256kb() {
        assertEquals(256 * 1024, SocketFileStreamer.BUFFER_BYTES)
        assertEquals(SocketFileStreamer.BUFFER_BYTES, FileApexClient.CHUNK_SIZE)
        assertEquals(128, TransferResumeProtocol.MIN_VERSION_CODE)
        assertEquals(4, TransferResumeProtocol.MAX_ATTEMPTS)
    }
}
