package com.fileapex.network

import com.fileapex.data.identity.LocalIdentity
import com.fileapex.domain.transfer.LiveTransferStats
import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.domain.transfer.TransferHistory
import com.fileapex.util.TimeUtils
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Inbound uploads must feed keyed receive progress to TransferActivityGuard without leaving stuck streams or send history. */
class ServerUploadProgressTest {
    private lateinit var peer: PinnedPeerFixture
    private lateinit var root: File

    @Before
    fun start() {
        TransferHistory.clear()
        TransferActivityGuard.reset()
        root = File(System.getProperty("java.io.tmpdir"), "fileapex-upload-progress-" + System.nanoTime()).also { it.mkdirs() }
        peer = PinnedPeerFixture { port, bridgePort ->
            FileApexServer(
                port = port,
                bridgePort = bridgePort,
                identityProvider = { LocalIdentity(deviceId = "self-test", deviceName = "Test", rootPath = root.absolutePath, sharePort = port) },
                isRosterMember = { it == "self-test" }
            )
        }.also { it.start() }
    }

    @After
    fun stop() {
        peer.stop()
        TransferActivityGuard.reset()
        TransferHistory.clear()
        root.deleteRecursively()
    }

    @Test
    fun `single upload publishes keyed receive progress and clears cleanly on finish`() {
        val payload = ByteArray(256 * 1024) { (it % 251).toByte() }
        val snapshots = CopyOnWriteArrayList<LiveTransferStats>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val collector = scope.launch {
            TransferActivityGuard.statsFlow.collect { snapshots.add(it) }
        }
        try {
            val target = URLEncoder.encode(File(root, "payload.bin").absolutePath, "UTF-8")
            val connection = peer.connection("/api/v1/files/upload?targetPath=$target&from=self-test&totalSize=${payload.size}")
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { out ->
                val chunk = 32 * 1024
                var offset = 0
                while (offset < payload.size) {
                    val count = minOf(chunk, payload.size - offset)
                    out.write(payload, offset, count)
                    out.flush()
                    offset += count
                    Thread.sleep(40)
                }
            }
            assertEquals(201, connection.responseCode)
            connection.disconnect()
        } finally {
            collector.cancel()
            scope.cancel()
        }

        val active = snapshots.filter { it.isActive }
        assertTrue("expected active stats snapshots", active.isNotEmpty())
        assertTrue("expected receive flag", active.any { it.isReceiving })
        assertTrue("expected filename payload.bin", active.any { it.currentFileName == "payload.bin" })
        assertTrue("expected known totalBytes", active.any { it.totalBytes == payload.size.toLong() })
        assertTrue("expected sentBytes > 0", active.any { it.sentBytes > 0L })
        assertTrue("expected progress > 0", active.any { it.progress > 0f })
        assertFalse("guard must be idle after upload completes", TransferActivityGuard.isTransferActive())
        assertTrue("inbound receive must not be recorded in send history", TransferHistory.visible(TimeUtils.now()).isEmpty())
    }

    @Test
    fun `segmented upload combines segment progress and clears after segments finish`() {
        val total = 192 * 1024
        val firstHalf = ByteArray(total / 2) { 1 }
        val secondHalf = ByteArray(total - firstHalf.size) { 2 }
        val snapshots = CopyOnWriteArrayList<LiveTransferStats>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val collector = scope.launch {
            TransferActivityGuard.statsFlow.collect { snapshots.add(it) }
        }
        try {
            val target = URLEncoder.encode(File(root, "segmented.bin").absolutePath, "UTF-8")
            fun sendSegment(offset: Int, bytes: ByteArray) {
                val conn = peer.connection(
                    "/api/v1/files/upload-segment?targetPath=$target&from=self-test&txId=tx-seg-1&totalSize=$total&offset=$offset&length=${bytes.size}"
                )
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { out ->
                    var pos = 0
                    while (pos < bytes.size) {
                        val n = minOf(16 * 1024, bytes.size - pos)
                        out.write(bytes, pos, n)
                        out.flush()
                        pos += n
                        Thread.sleep(30)
                    }
                }
                assertEquals(200, conn.responseCode)
                conn.disconnect()
            }
            sendSegment(0, firstHalf)
            sendSegment(firstHalf.size, secondHalf)
        } finally {
            collector.cancel()
            scope.cancel()
        }

        val active = snapshots.filter { it.isActive }
        assertTrue("expected active stats snapshots", active.isNotEmpty())
        assertTrue("expected receive flag", active.any { it.isReceiving })
        assertTrue("expected totalSize $total", active.any { it.totalBytes == total.toLong() })
        assertTrue("expected combined progress past midpoint", active.any { it.sentBytes > firstHalf.size.toLong() })
        assertFalse("guard must be idle after segments complete", TransferActivityGuard.isTransferActive())
    }

    @Test
    fun `aborted upload clears guard without leaving a stuck stream`() {
        val target = URLEncoder.encode(File(root, "aborted.bin").absolutePath, "UTF-8")
        val conn = peer.connection("/api/v1/files/upload?targetPath=$target&from=self-test&totalSize=100000")
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setChunkedStreamingMode(1024)
        conn.outputStream.write(ByteArray(4096) { 7 })
        conn.outputStream.flush()
        conn.disconnect()
        val deadline = System.currentTimeMillis() + 3_000L
        while (TransferActivityGuard.isTransferActive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertFalse("guard must clear after aborted upload", TransferActivityGuard.isTransferActive())
    }
}
