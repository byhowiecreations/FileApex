package com.fileapex.notice

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeVerifierTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    private val publicKey = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    private val signature = hex(
        "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
    )

    @Test
    fun theRfc8032FirstVectorVerifies() {
        assertArrayEquals(publicKey, Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded)
        assertTrue(NoticeVerifier.verify(ByteArray(0), signature, listOf(publicKey)))
    }

    @Test
    fun theRfc8032VectorFailsForAnotherMessageOrAFlippedBit() {
        assertFalse(NoticeVerifier.verify(byteArrayOf(0), signature, listOf(publicKey)))
        val flipped = signature.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertFalse(NoticeVerifier.verify(ByteArray(0), flipped, listOf(publicKey)))
    }

    @Test
    fun malformedKeysAndSignaturesAreRefusedWithoutThrowing() {
        assertFalse(NoticeVerifier.verify(ByteArray(0), signature, listOf(ByteArray(31))))
        assertFalse(NoticeVerifier.verify(ByteArray(0), signature, listOf(ByteArray(33))))
        assertFalse(NoticeVerifier.verify(ByteArray(0), ByteArray(63), listOf(publicKey)))
        assertFalse(NoticeVerifier.verify(ByteArray(0), signature, emptyList()))
    }

    @Test
    fun aBadKeyInTheListDoesNotStopTheGoodOne() {
        assertTrue(NoticeVerifier.verify(ByteArray(0), signature, listOf(ByteArray(5), publicKey)))
    }
}
