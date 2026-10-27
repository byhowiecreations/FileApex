package com.fileapex.network

import com.fileapex.data.db.PairedDeviceEntity
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeHelloTest {
    /** Both ends of a pair derive the same key from the two public keys; a third party cannot. */
    private class FakeAuth(private val ownPub: String) : PairAuthenticator {
        override fun authenticate(data: ByteArray, localDeviceId: String, peerDeviceId: String, peerPublicKeyBase64: String): ByteArray {
            val key = listOf(ownPub, peerPublicKeyBase64).sorted().joinToString("+") + "|" +
                listOf(localDeviceId, peerDeviceId).sorted().joinToString("|")
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key.encodeToByteArray(), "HmacSHA256"))
            return mac.doFinal(data)
        }
    }

    private fun device(id: String, removed: Boolean = false, key: String = "pub-$id") =
        PairedDeviceEntity(
            deviceId = id, deviceName = id, lastKnownIp = "10.0.0.1", port = 8080, publicKeyHash = "",
            rootPath = "/", publicKey = key, isRemoved = removed
        )

    private val authA = FakeAuth("pub-A")
    private val authB = FakeAuth("pub-B")
    private val now = 10_000_000L
    private val nonce = ByteArray(8) { (it + 1).toByte() }

    private fun hello(
        recipients: List<WakeRecipient> = listOf(WakeRecipient("B", "pub-B")),
        at: Long = now,
        n: ByteArray = nonce
    ) = WakeHello.build("A", 8080, recipients, at, n, authA)

    @Test
    fun aPairedReceiverVerifiesAndLearnsTheSenderAndPort() {
        val parsed = WakeHello.parse(hello().single())!!

        assertEquals("A", parsed.senderId)
        assertEquals(8080, parsed.port)
        assertTrue(WakeHello.verify(parsed, "B", device("A"), now + 5_000, authB))
    }

    @Test
    fun aReceiverNotInTheListIgnoresIt() {
        val parsed = WakeHello.parse(hello().single())!!

        assertFalse(WakeHello.verify(parsed, "C", device("A"), now, FakeAuth("pub-C")))
    }

    @Test
    fun aForgedSenderWithTheWrongKeyFails() {
        val forged = WakeHello.build("A", 8080, listOf(WakeRecipient("B", "pub-B")), now, nonce, FakeAuth("pub-evil"))
        val parsed = WakeHello.parse(forged.single())!!

        assertFalse(WakeHello.verify(parsed, "B", device("A"), now, authB))
    }

    @Test
    fun aChangedSenderIdOrPortBreaksTheProof() {
        val bytes = hello().single()
        val changedPort = bytes.copyOf()
        val portAt = 4 + 1 + 1 // magic, id length, one id byte
        changedPort[portAt] = (changedPort[portAt].toInt() xor 1).toByte()

        assertFalse(WakeHello.verify(WakeHello.parse(changedPort)!!, "B", device("A"), now, authB))
    }

    @Test
    fun aStaleOrFutureHelloIsRejected() {
        val parsed = WakeHello.parse(hello().single())!!

        assertFalse(WakeHello.verify(parsed, "B", device("A"), now + WakeHello.MAX_SKEW_MS + 1, authB))
        assertFalse(WakeHello.verify(parsed, "B", device("A"), now - WakeHello.MAX_SKEW_MS - 1, authB))
    }

    @Test
    fun aRemovedOrDifferentlyKeyedSenderIsRejected() {
        val parsed = WakeHello.parse(hello().single())!!

        assertFalse(WakeHello.verify(parsed, "B", device("A", removed = true), now, authB))
        assertFalse(WakeHello.verify(parsed, "B", device("X"), now, authB))
        assertFalse(WakeHello.verify(parsed, "B", device("A", key = ""), now, authB))
    }

    @Test
    fun garbageAndTruncatedPacketsParseToNull() {
        val good = hello().single()

        assertNull(WakeHello.parse(PLAIN_WAKE))
        assertNull(WakeHello.parse(ByteArray(0)))
        assertNull(WakeHello.parse(good.copyOf(good.size - 1)))
        assertNull(WakeHello.parse(good.copyOf(10)))
    }

    @Test
    fun aLargeRosterIsSplitAcrossPacketsAndEachPeerIsInExactlyOne() {
        val recipients = (1..70).map { WakeRecipient("P$it", "pub-P$it") }
        val packets = hello(recipients)

        assertTrue(packets.size > 1)
        assertTrue(packets.all { it.size <= WakeHello.MAX_PACKET })
        for (r in recipients) {
            val ok = packets.count { WakeHello.verify(WakeHello.parse(it)!!, r.deviceId, device("A"), now, FakeAuth(r.publicKey)) }
            assertEquals(r.deviceId, 1, ok)
        }
    }

    @Test
    fun noRecipientsMeansNoPacket() {
        assertTrue(hello(emptyList()).isEmpty())
    }

    @Test
    fun theGateDropsAReplayedNonceAndARapidRepeatFromTheSameSender() {
        var clock = now
        val gate = WakeHelloGate { clock }

        assertTrue(gate.accept("A", nonce))
        assertFalse(gate.accept("A", nonce))
        clock += 1_000
        assertFalse(gate.accept("A", ByteArray(8) { 9 }))
        clock += WakeHelloGate.MIN_GAP_MS
        assertTrue(gate.accept("A", ByteArray(8) { 9 }))
        assertTrue(gate.accept("Z", nonce))
    }

    @Test
    fun theLegacyWakeStringIsNotAHello() {
        assertNotNull(WakeHello.parse(hello().single()))
        assertFalse(WakeHello.looksSigned(PLAIN_WAKE))
        assertTrue(WakeHello.looksSigned(hello().single()))
    }

    @Test
    fun anAckIsMarkedAndVerifiesLikeAHello() {
        val ack = WakeHello.build("A", 8080, listOf(WakeRecipient("B", "pub-B")), now, nonce, authA, ack = true).single()
        val parsed = WakeHello.parse(ack)!!

        assertTrue(parsed.isAck)
        assertFalse(WakeHello.parse(hello().single())!!.isAck)
        assertTrue(WakeHello.verify(parsed, "B", device("A"), now, authB))
    }

    @Test
    fun anAckCannotBePassedOffAsAHelloByChangingItsMagic() {
        val ack = WakeHello.build("A", 8080, listOf(WakeRecipient("B", "pub-B")), now, nonce, authA, ack = true).single()
        val asHello = ack.copyOf()
        asHello[2] = 'H'.code.toByte()
        asHello[3] = '1'.code.toByte()
        asHello[1] = 'A'.code.toByte()
        asHello[0] = 'F'.code.toByte()

        assertFalse(WakeHello.verify(WakeHello.parse(asHello)!!, "B", device("A"), now, authB))
    }

    @Test
    fun theResendComesAfterTheReceiversPerSenderGap() {
        assertTrue(com.fileapex.domain.presence.LanPresenceTiming.HELLO_RESEND_MS > WakeHelloGate.MIN_GAP_MS)
    }

    @Test
    fun thePlainWakeStringIsNeverSentAndNeverCountsAsSigned() {
        WakeHelloOutbox.setRoster(emptyList())
        assertTrue(WakeHelloOutbox.payloads().none { it.contentEquals(PLAIN_WAKE) })
        assertFalse(WakeHello.looksSigned(PLAIN_WAKE))
        assertNull(WakeHello.parse(PLAIN_WAKE))
    }

    private companion object {
        /** The unsigned wake of builds before 0.19.0; removed in 0.19.8, so a receiver must drop it. */
        val PLAIN_WAKE = "WAKE_FILEAPEX".encodeToByteArray()
    }
}
