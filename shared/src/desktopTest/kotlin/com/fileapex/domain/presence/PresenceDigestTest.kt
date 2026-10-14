package com.fileapex.domain.presence

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.security.tls.PairCrypto
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceDigestTest {
    /** Both ends of a pair derive the same key from the two public keys; a third party cannot. */
    private class FakeCrypto(private val ownId: String) : PairCrypto {
        private val ownPub = "pub-$ownId"
        override fun publicKeyBase64() = ownPub
        private fun pairKey(peerPub: String) = listOf(ownPub, peerPub).sorted().joinToString("+")
        override fun encrypt(plaintext: ByteArray, localDeviceId: String, peerDeviceId: String, peerPublicKeyBase64: String) =
            pairKey(peerPublicKeyBase64) + "|" + Base64.getEncoder().encodeToString(plaintext)

        override fun decrypt(ciphertextBase64: String, localDeviceId: String, peerDeviceId: String, peerPublicKeyBase64: String): ByteArray {
            val (key, body) = ciphertextBase64.split("|", limit = 2)
            require(key == pairKey(peerPublicKeyBase64)) { "wrong key" }
            return Base64.getDecoder().decode(body)
        }
    }

    private fun device(id: String, removed: Boolean = false) =
        PairedDeviceEntity(
            deviceId = id, deviceName = id, lastKnownIp = "10.0.0.1", port = 8080, publicKeyHash = "",
            rootPath = "/", publicKey = "pub-$id", isRemoved = removed
        )

    private val a = device("A")
    private val b = device("B")
    private val now = 1_000_000L

    private fun ask(stamp: String = PresenceDigests.newStamp()) =
        PresenceDigests.buildAsk("B", a, stamp, now, FakeCrypto("B"))!! to stamp

    @Test
    fun stampsAreLongRandomAndDifferent() {
        val first = PresenceDigests.newStamp()
        assertEquals(32, first.length)
        assertNotEquals(first, PresenceDigests.newStamp())
    }

    @Test
    fun pairedPeerGetsRecentPeersWithRelativeAges() {
        val (message, stamp) = ask()
        val answer = PresenceDigests.answer(
            message, sender = b, localDeviceId = "A",
            members = setOf("A", "B", "C", "D", "E"),
            verifiedAtMs = mapOf("B" to now - 1, "C" to now - 40_000, "D" to now - 400_000, "E" to now - 10_000, "Z" to now),
            nowMs = now, crypto = FakeCrypto("A")
        )
        val reply = (answer as DigestAnswer.Reply).message
        val opened = PresenceDigests.open(reply, a, stamp, "B", FakeCrypto("B"))!!
        assertEquals(listOf("E", "C"), opened.seen.map { it.id })
        assertEquals(listOf(10_000L, 40_000L), opened.seen.map { it.ageMs })
        assertEquals(now, opened.sentAtUtcMs)
    }

    @Test
    fun unknownRemovedAndWrongKeySendersGetNothing() {
        val (message, _) = ask()
        val verified = mapOf("C" to now)
        fun answerTo(sender: PairedDeviceEntity?, msg: PresenceDigestMessage = message) = PresenceDigests.answer(
            msg, sender, "A", setOf("C"), verified, now, FakeCrypto("A")
        )
        assertTrue(answerTo(null) is DigestAnswer.Denied)
        assertTrue(answerTo(device("B", removed = true)) is DigestAnswer.Denied)
        assertTrue(answerTo(b.copy(publicKey = "pub-other")) is DigestAnswer.Denied)
        assertTrue(answerTo(b, message.copy(senderPublicKey = "pub-forged")) is DigestAnswer.Denied)
        // A device that knows B's id but not the pair key cannot produce a readable ask.
        val forged = PresenceDigests.buildAsk("B", a, "x".repeat(32), now, FakeCrypto("MALLORY"))!!
        assertTrue(answerTo(b, forged.copy(senderPublicKey = "pub-B")) is DigestAnswer.Denied)
    }

    @Test
    fun replyIsOnlyAcceptedFromTheAskedPeerForThatAsk() {
        val (message, stamp) = ask()
        val reply = (PresenceDigests.answer(message, b, "A", setOf("C"), mapOf("C" to now), now, FakeCrypto("A")) as DigestAnswer.Reply).message
        assertNotNull(PresenceDigests.open(reply, a, stamp, "B", FakeCrypto("B")))
        assertNull("replayed against another ask", PresenceDigests.open(reply, a, PresenceDigests.newStamp(), "B", FakeCrypto("B")))
        assertNull("claimed by another peer", PresenceDigests.open(reply.copy(senderDeviceId = "C"), device("C"), stamp, "B", FakeCrypto("B")))
        assertNull("unreadable by a third device", PresenceDigests.open(reply, a, stamp, "M", FakeCrypto("MALLORY")))
    }

    @Test
    fun peerWithoutKeyCannotBeAsked() {
        assertNull(PresenceDigests.buildAsk("B", a.copy(publicKey = ""), "s".repeat(32), now, FakeCrypto("B")))
    }

    @Test
    fun aPeerIsSkippedOnceThenMustBeReachedDirectly() {
        val log = DigestSkipLog()
        assertTrue(log.trySkip("C"))
        assertFalse(log.trySkip("C"))
        assertTrue(log.trySkip("D"))
        log.directContact("C")
        assertTrue(log.trySkip("C"))
    }
}
