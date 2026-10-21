package com.fileapex.network

import com.fileapex.data.identity.LocalIdentity
import com.fileapex.iroh.IrohBridgePorts
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Behavior of the client's transfer engine (downloads, uploads, resume, relay) against the real
 * server on loopback. These tests pin what the engine does today so it can move to its own class unchanged.
 */
class PeerTransferEngineTest {
    private class Peer(val server: FileApexServer, val bridge: IrohStyleBridge, val port: Int, val root: File)

    private val peers = mutableListOf<Peer>()
    private lateinit var local: File
    private val client = FileApexClient(localDeviceId = { "self-test" })

    @Before
    fun setUp() {
        local = File(System.getProperty("java.io.tmpdir"), "fileapex-engine-local-" + System.nanoTime()).also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        peers.forEach { it.bridge.close(); it.server.stop(); it.root.deleteRecursively() }
        IrohBridgePorts.clear()
        local.deleteRecursively()
    }

    private fun startPeer(): Peer {
        val root = File(System.getProperty("java.io.tmpdir"), "fileapex-engine-peer-" + System.nanoTime()).also { it.mkdirs() }
        val port = ServerSocket(0).use { it.localPort }
        val bridgePort = ServerSocket(0, 1, java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { it.localPort }
        val server = FileApexServer(
            port = port,
            bridgePort = bridgePort,
            identityProvider = { LocalIdentity(deviceId = "self-test", deviceName = "Peer", rootPath = root.absolutePath, sharePort = port) },
            isRosterMember = { true }
        ).also { it.start() }
        Thread.sleep(500)
        // The client reaches the server the way an Iroh peer does: plain HTTP to a local bridge port that vouches for the caller.
        val bridge = IrohStyleBridge(bridgePort, "self-test")
        IrohBridgePorts.add(bridge.port)
        return Peer(server, bridge, bridge.port, root).also { peers += it }
    }

    private fun payload(size: Int): ByteArray = ByteArray(size) { (it * 31 + it / 7).toByte() }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun downloadStoresTheFileAndReportsProgress() = runBlocking {
        val peer = startPeer()
        val data = payload(300_000)
        File(peer.root, "a.bin").writeBytes(data)
        val target = File(local, "a.bin")
        var lastReceived = 0L

        client.downloads.downloadToLocal(
            "127.0.0.1", peer.port, File(peer.root, "a.bin").absolutePath, target.absolutePath,
            expectedSizeBytes = data.size.toLong()
        ) { received, total -> lastReceived = received; assertEquals(data.size.toLong(), total) }

        assertArrayEquals(data, target.readBytes())
        assertEquals(data.size.toLong(), lastReceived)
        assertFalse("part file must be renamed away", File(local, "a.bin.part").exists())
    }

    @Test
    fun downloadResumesFromAnExistingPart() = runBlocking {
        val peer = startPeer()
        val data = payload(400_000)
        File(peer.root, "r.bin").writeBytes(data)
        val target = File(local, "r.bin")
        File(local, "r.bin.part").writeBytes(data.copyOfRange(0, 150_000))

        client.downloads.downloadToLocal(
            "127.0.0.1", peer.port, File(peer.root, "r.bin").absolutePath, target.absolutePath,
            expectedSizeBytes = data.size.toLong()
        )

        assertArrayEquals(data, target.readBytes())
    }

    @Test
    fun downloadThatIsShorterThanExpectedFailsAndLeavesNoFinalFile() = runBlocking {
        val peer = startPeer()
        File(peer.root, "s.bin").writeBytes(payload(10_000))
        val target = File(local, "s.bin")

        val failure = runCatching {
            client.downloads.downloadToLocal(
                "127.0.0.1", peer.port, File(peer.root, "s.bin").absolutePath, target.absolutePath,
                expectedSizeBytes = 20_000L
            )
        }.exceptionOrNull()

        assertTrue("size mismatch must fail", failure != null)
        assertFalse(target.exists())
    }

    @Test
    fun downloadBytesReturnsTheContentAndEnforcesTheCap() = runBlocking {
        val peer = startPeer()
        val data = payload(50_000)
        File(peer.root, "b.bin").writeBytes(data)
        val path = File(peer.root, "b.bin").absolutePath

        assertArrayEquals(data, client.downloads.downloadBytes("127.0.0.1", peer.port, path))
        val failure = runCatching { client.downloads.downloadBytes("127.0.0.1", peer.port, path, maxBytes = 1_000L) }.exceptionOrNull()
        assertTrue("cap must stop the read", failure != null)
    }

    @Test
    fun uploadDeliversTheFileAndVerifiesItsHash() = runBlocking {
        val peer = startPeer()
        val data = payload(250_000)
        val source = File(local, "u.bin").also { it.writeBytes(data) }
        val remote = File(peer.root, "u.bin")
        var sent = 0L

        client.uploads.uploadFromLocal("127.0.0.1", peer.port, source.absolutePath, remote.absolutePath) { s, _ -> sent = s }

        assertEquals(sha(data), sha(remote.readBytes()))
        assertEquals(data.size.toLong(), sent)
    }

    @Test
    fun backupResumeOffsetComesFromThePartOfTheFileBeingReplaced() = runBlocking {
        val peer = startPeer()
        val remote = File(peer.root, "backup.bin")
        remote.writeBytes(payload(900))
        File(SocketFileStreamer.partPathFor(remote.absolutePath, "tx-backup")).writeBytes(payload(150_000))

        val backupOffset = client.uploads.queryUploadResumeOffset(
            "127.0.0.1", peer.port, remote.absolutePath,
            expectedSizeBytes = 250_000L, transactionId = "tx-backup", backup = true
        )
        assertEquals(150_000L, backupOffset)

        // The same part is not the resume point of a fresh, numbered copy.
        val freshOffset = client.uploads.queryUploadResumeOffset(
            "127.0.0.1", peer.port, remote.absolutePath,
            expectedSizeBytes = 250_000L, transactionId = "tx-backup", backup = false
        )
        assertEquals(0L, freshOffset)
    }

    @Test
    fun backupUploadReplacesTheEarlierCopyAndResumesItsPart() = runBlocking {
        val peer = startPeer()
        val data = payload(250_000)
        val source = File(local, "backup.bin").also { it.writeBytes(data) }
        val remote = File(peer.root, "backup.bin")
        remote.writeBytes(payload(900))
        File(SocketFileStreamer.partPathFor(remote.absolutePath, "tx-backup"))
            .writeBytes(data.copyOfRange(0, 150_000))

        client.uploads.uploadFromLocal(
            "127.0.0.1", peer.port, source.absolutePath, remote.absolutePath,
            transactionId = "tx-backup", transactionTimestampEpochMs = System.currentTimeMillis(),
            backup = true
        )

        assertArrayEquals(data, remote.readBytes())
        assertFalse(File(peer.root, "backup (1).bin").exists())
    }

    @Test
    fun twoSameNameUploadsWithDifferentTransactionsBothArriveIntact() = runBlocking {
        val peer = startPeer()
        val first = ByteArray(300_000) { 11 }
        val second = ByteArray(300_000) { 77 }
        val sourceA = File(local, "a.bin").also { it.writeBytes(first) }
        val sourceB = File(local, "b.bin").also { it.writeBytes(second) }
        val remote = File(peer.root, "same.bin")

        coroutineScope {
            val a = async {
                client.uploads.uploadFromLocal(
                    "127.0.0.1", peer.port, sourceA.absolutePath, remote.absolutePath,
                    transactionId = "tx-same-a", transactionTimestampEpochMs = System.currentTimeMillis()
                )
            }
            val b = async {
                client.uploads.uploadFromLocal(
                    "127.0.0.1", peer.port, sourceB.absolutePath, remote.absolutePath,
                    transactionId = "tx-same-b", transactionTimestampEpochMs = System.currentTimeMillis()
                )
            }
            a.await(); b.await()
        }

        val arrived = listOf(File(peer.root, "same.bin"), File(peer.root, "same (1).bin"))
        assertTrue(arrived.all { it.isFile })
        val contents = arrived.map { it.readBytes().toList() }.toSet()
        assertEquals(setOf(first.toList(), second.toList()), contents)
    }

    @Test
    fun resumeOffsetOfAnUnknownTargetIsZero() = runBlocking {
        val peer = startPeer()
        val offset = client.uploads.queryUploadResumeOffset(
            "127.0.0.1", peer.port, File(peer.root, "nothing.bin").absolutePath, expectedSizeBytes = 1_000L, transactionId = "tx-1"
        )
        assertEquals(0L, offset)
    }

    @Test
    fun relayCopiesAFileFromOnePeerToAnother() = runBlocking {
        val source = startPeer()
        val dest = startPeer()
        val data = payload(200_000)
        File(source.root, "x.bin").writeBytes(data)
        val remote = File(dest.root, "x.bin")

        client.uploads.relayRemoteFile(
            "127.0.0.1", source.port, File(source.root, "x.bin").absolutePath, data.size.toLong(),
            "127.0.0.1", dest.port, remote.absolutePath,
            transactionId = "tx-relay", transactionTimestampEpochMs = System.currentTimeMillis()
        )

        assertArrayEquals(data, remote.readBytes())
    }

    @Test
    fun capabilitiesAreReadFromThePeer() = runBlocking {
        val peer = startPeer()
        val caps = client.transferCapabilities.of("127.0.0.1", peer.port)
        assertTrue(caps.rangedStream)
        assertTrue(caps.segmentedUpload)
    }

    private val largeSize = (TransferRuntime.LARGE_FILE_THRESHOLD_BYTES + 3_000_001L).toInt()

    private fun writeLarge(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val block = payload(1 shl 20)
        file.outputStream().use { out ->
            var left = largeSize
            while (left > 0) {
                val n = minOf(left, block.size)
                out.write(block, 0, n); digest.update(block, 0, n); left -= n
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun largeDownloadArrivesAsParallelRanges() = runBlocking {
        val peer = startPeer()
        val expected = writeLarge(File(peer.root, "big.bin"))
        val target = File(local, "big.bin")

        client.downloads.downloadToLocal(
            "127.0.0.1", peer.port, File(peer.root, "big.bin").absolutePath, target.absolutePath,
            expectedSizeBytes = largeSize.toLong()
        )

        assertEquals(largeSize.toLong(), target.length())
        assertEquals(expected, sha(target))
    }

    @Test
    fun largeUploadArrivesAsParallelRanges() = runBlocking {
        val peer = startPeer()
        val source = File(local, "bigup.bin")
        val expected = writeLarge(source)
        val remote = File(peer.root, "bigup.bin")

        client.uploads.uploadFromLocal(
            "127.0.0.1", peer.port, source.absolutePath, remote.absolutePath,
            transactionId = "tx-big", transactionTimestampEpochMs = System.currentTimeMillis()
        )

        assertEquals(largeSize.toLong(), remote.length())
        assertEquals(expected, sha(remote))
    }

    @Test
    fun largeRelayArrivesAsParallelRanges() = runBlocking {
        val source = startPeer()
        val dest = startPeer()
        val expected = writeLarge(File(source.root, "bigrelay.bin"))
        val remote = File(dest.root, "bigrelay.bin")

        client.uploads.relayRemoteFile(
            "127.0.0.1", source.port, File(source.root, "bigrelay.bin").absolutePath, largeSize.toLong(),
            "127.0.0.1", dest.port, remote.absolutePath,
            transactionId = "tx-bigrelay", transactionTimestampEpochMs = System.currentTimeMillis()
        )

        assertEquals(largeSize.toLong(), remote.length())
        assertEquals(expected, sha(remote))
    }
}
