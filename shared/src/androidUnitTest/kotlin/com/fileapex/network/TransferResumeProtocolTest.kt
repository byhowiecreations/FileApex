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

    @Test
    fun inspectIncomingBackupResumesThePartOfTheFileItReplaces() {
        val dir = File.createTempFile("fileapex-backup-resume-", ".dir")
        check(dir.delete() && dir.mkdirs())
        try {
            val preferred = File(dir, "report.txt").absolutePath
            File(preferred).writeBytes(ByteArray(900) { 1 })
            File(SocketFileStreamer.partPathFor(preferred)).writeBytes(ByteArray(1500) { 7 })

            val backup = TransferResumeProtocol.inspectIncoming(preferred, expectedSize = 4000L, backup = true)
            assertEquals(1500L, backup.offset)
            assertFalse(backup.complete)

            // Without backup the earlier copy is not the target: a numbered name is, and it has no part.
            val fresh = TransferResumeProtocol.inspectIncoming(preferred, expectedSize = 4000L, backup = false)
            assertEquals(0L, fresh.offset)
            assertFalse(fresh.complete)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun inspectIncomingBackupFinalizesInPlaceWhenThePartIsComplete() {
        val dir = File.createTempFile("fileapex-backup-done-", ".dir")
        check(dir.delete() && dir.mkdirs())
        try {
            val preferred = File(dir, "report.txt").absolutePath
            File(preferred).writeBytes(ByteArray(900) { 1 })
            File(SocketFileStreamer.partPathFor(preferred)).writeBytes(ByteArray(4000) { 8 })

            val done = TransferResumeProtocol.inspectIncoming(preferred, expectedSize = 4000L, backup = true)
            assertTrue(done.complete)
            assertEquals(4000L, File(preferred).length())
            assertTrue(File(preferred).readBytes().all { it == 8.toByte() })
            assertFalse(File(dir, "report (1).txt").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun inspectIncomingKeepsSameNameTransfersApartByTransaction() {
        val dir = File.createTempFile("fileapex-tx-parts-", ".dir")
        check(dir.delete() && dir.mkdirs())
        try {
            val preferred = File(dir, "photo.jpg").absolutePath
            File(SocketFileStreamer.partPathFor(preferred, "tx-a")).writeBytes(ByteArray(1200) { 3 })

            val a = TransferResumeProtocol.inspectIncoming(preferred, expectedSize = 4000L, transactionId = "tx-a")
            assertEquals(1200L, a.offset)
            val b = TransferResumeProtocol.inspectIncoming(preferred, expectedSize = 4000L, transactionId = "tx-b")
            assertEquals(0L, b.offset)
            val noTx = TransferResumeProtocol.inspectIncoming(preferred, expectedSize = 4000L)
            assertEquals(0L, noTx.offset)

            assertFalse(SocketFileStreamer.partPathFor(preferred, "tx-a") == SocketFileStreamer.partPathFor(preferred, "tx-b"))
            assertFalse(SocketFileStreamer.segmentPartPathFor(preferred, "tx-a") == SocketFileStreamer.segmentPartPathFor(preferred, "tx-b"))
            // A hostile transaction id stays a plain file name in the same folder.
            val hostile = SocketFileStreamer.partPathFor(preferred, "../../evil")
            assertEquals(dir.absolutePath, File(hostile).parent)
            assertTrue(SocketFileStreamer.isTransferScratchFile(File(hostile).name))
            assertTrue(SocketFileStreamer.isTransferScratchFile(File(SocketFileStreamer.partPathFor(preferred, "tx-a")).name))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun transactionJournalDeduplicatesSameTransactionAndAllowsFreshTransfer() {
        val dir = File.createTempFile("fileapex-tx-journal-", ".dir")
        check(dir.delete() && dir.mkdirs())
        try {
            val file = File(dir, "FileApex-test.apk")
            file.writeBytes(ByteArray(2048) { 42 })

            val txId1 = "tx-event-1"
            val sender = "mac-client"
            TransferTransactionJournal.recordCompleted(
                transactionId = txId1,
                senderDeviceId = sender,
                targetPath = file.absolutePath,
                finalPath = file.absolutePath,
                byteSize = 2048L,
                timestampEpochMs = 123456789L
            )

            val sameTx = TransferResumeProtocol.inspectIncoming(
                preferredPath = file.absolutePath,
                expectedSize = 2048L,
                transactionId = txId1,
                senderDeviceId = sender
            )
            assertEquals(2048L, sameTx.offset)
            assertTrue(sameTx.complete)

            val txId2 = "tx-event-2"
            val newTx = TransferResumeProtocol.inspectIncoming(
                preferredPath = file.absolutePath,
                expectedSize = 2048L,
                transactionId = txId2,
                senderDeviceId = sender
            )
            // Since no part file exists for fresh target, returns 0L offset and not complete
            assertEquals(0L, newTx.offset)
            assertFalse(newTx.complete)

            assertFalse(TransferTransactionJournal.isInstalled(txId1))
            TransferTransactionJournal.markInstallAttempted(txId1)
            assertFalse(TransferTransactionJournal.isInstalled(txId1))
            val recordBefore = TransferTransactionJournal.findRecordByFilePath(file.absolutePath)
            assertTrue(recordBefore?.installAttempted == true)

            TransferTransactionJournal.markInstalled(txId1)
            assertTrue(TransferTransactionJournal.isInstalled(txId1))
            val recordAfter = TransferTransactionJournal.findRecordByFilePath(file.absolutePath)
            assertTrue(recordAfter?.installed == true)

            TransferTransactionJournal.purgeTransaction(txId1)
            assertFalse(TransferTransactionJournal.isInstalled(txId1))
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
