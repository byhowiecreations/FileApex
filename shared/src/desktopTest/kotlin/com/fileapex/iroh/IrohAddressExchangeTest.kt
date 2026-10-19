package com.fileapex.iroh

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.security.tls.PairCrypto
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IrohAddressExchangeTest {
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
    private val keyA = "a".repeat(64)
    private val keyB = "b".repeat(64)
    private val addressA = "$keyA|https://relay.example.net/|192.168.1.10:5000,[fe80::1]:5000"
    private val addressB = "$keyB||192.168.1.20:6000"

    private fun ask(stamp: String = IrohAddressExchange.newStamp(), at: Long = now) =
        IrohAddressExchange.buildAsk("B", a, addressB, stamp, at, FakeCrypto("B"))!!

    @Test
    fun bothSidesLearnTheOthersAddress() {
        val stamp = IrohAddressExchange.newStamp()
        val answer = IrohAddressExchange.answer(ask(stamp), b, "A", addressA, now, FakeCrypto("A"))

        val reply = answer as AddressAnswer.Reply
        assertEquals(addressB, reply.learned)
        assertEquals(addressA, IrohAddressExchange.open(reply.message, a, stamp, "B", FakeCrypto("B")))
    }

    @Test
    fun unknownRemovedAndForgedSendersGetNothing() {
        val message = ask()
        fun answerTo(sender: PairedDeviceEntity?, msg: IrohAddressMessage = message) =
            IrohAddressExchange.answer(msg, sender, "A", addressA, now, FakeCrypto("A"))
        assertTrue(answerTo(null) is AddressAnswer.Denied)
        assertTrue(answerTo(device("B", removed = true)) is AddressAnswer.Denied)
        assertTrue(answerTo(b.copy(publicKey = "pub-other")) is AddressAnswer.Denied)
        assertTrue(answerTo(b, message.copy(senderPublicKey = "pub-forged")) is AddressAnswer.Denied)
        val forged = IrohAddressExchange.buildAsk("B", a, addressB, "x".repeat(32), now, FakeCrypto("MALLORY"))!!
        assertTrue(answerTo(b, forged.copy(senderPublicKey = "pub-B")) is AddressAnswer.Denied)
    }

    @Test
    fun staleAndFutureAsksAreInvalid() {
        val tenMinutes = 10 * 60_000L
        for (at in listOf(now - tenMinutes - 1, now + tenMinutes + 1)) {
            val answer = IrohAddressExchange.answer(ask(at = at), b, "A", addressA, now, FakeCrypto("A"))
            assertTrue(answer is AddressAnswer.Invalid)
        }
    }

    @Test
    fun replyForAnotherAskIsRejected() {
        val reply = (IrohAddressExchange.answer(ask("s".repeat(32)), b, "A", addressA, now, FakeCrypto("A")) as AddressAnswer.Reply).message

        assertNull(IrohAddressExchange.open(reply, a, "t".repeat(32), "B", FakeCrypto("B")))
    }

    @Test
    fun askCarryingAGarbageAddressIsInvalidAndNothingIsLearned() {
        val bad = IrohAddressExchange.buildAsk("B", a, "not an address", IrohAddressExchange.newStamp(), now, FakeCrypto("B"))!!

        assertTrue(IrohAddressExchange.answer(bad, b, "A", addressA, now, FakeCrypto("A")) is AddressAnswer.Invalid)
    }

    @Test
    fun addressValidationRejectsHostileShapes() {
        assertTrue(IrohAddressExchange.isValidAddress(addressA))
        assertTrue(IrohAddressExchange.isValidAddress(addressB))
        assertFalse(IrohAddressExchange.isValidAddress(""))
        assertFalse(IrohAddressExchange.isValidAddress("zz|$keyA|"))
        assertFalse(IrohAddressExchange.isValidAddress("${"g".repeat(64)}||1.2.3.4:5"))
        assertFalse(IrohAddressExchange.isValidAddress("${keyA.take(63)}||1.2.3.4:5"))
        assertFalse(IrohAddressExchange.isValidAddress("$keyA|http://relay.example.net/|1.2.3.4:5"))
        assertFalse(IrohAddressExchange.isValidAddress("$keyA||1.2.3.4:5, evil"))
        assertFalse(IrohAddressExchange.isValidAddress("$keyA||" + (1..40).joinToString(",") { "10.0.0.$it:1" }))
        assertFalse(IrohAddressExchange.isValidAddress("$keyA||" + "x".repeat(5000)))
        assertFalse(IrohAddressExchange.isValidAddress("$keyA|https://relay.example.net/\n|1.2.3.4:5"))
    }

    @Test
    fun peerBookKeepsOnlyValidAddressesAndSurvivesARoundTrip() {
        var book = IrohPeerBook.empty()
        book = book.with("A", addressA)
        book = book.with("B", "garbage")
        book = book.with("C", addressB)

        val restored = IrohPeerBook.parse(book.toJson())

        assertEquals(addressA, restored.address("A"))
        assertNull(restored.address("B"))
        assertEquals(keyB, restored.endpointId("C"))
        assertEquals("C", restored.deviceIdFor(keyB))
        assertNull(restored.deviceIdFor("f".repeat(64)))
    }

    @Test
    fun peerBookIgnoresBrokenJsonAndDropsRemovedDevices() {
        assertEquals(0, IrohPeerBook.parse("{not json").size)
        val book = IrohPeerBook.empty().with("A", addressA).with("C", addressB)

        val pruned = book.keepOnly(setOf("A"))

        assertNotNull(pruned.address("A"))
        assertNull(pruned.address("C"))
    }

    @Test
    fun peerBookIsCapped() {
        var book = IrohPeerBook.empty()
        repeat(100) { i -> book = book.with("dev-$i", "${"%064x".format(i)}||10.0.0.1:1") }

        assertEquals(IrohPeerBook.MAX_PEERS, book.size)
    }
}
