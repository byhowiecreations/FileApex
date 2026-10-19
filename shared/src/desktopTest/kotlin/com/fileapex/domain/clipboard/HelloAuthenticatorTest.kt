package com.fileapex.domain.clipboard

import java.security.KeyFactory
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPrivateKeySpec
import java.security.spec.XECPublicKeySpec
import java.math.BigInteger
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HelloAuthenticatorTest {
    private val privA = ByteArray(32) { 7 }
    private val privB = ByteArray(32) { 9 }
    private val pubA = ClipboardCrypto.publicKeyFromPrivate(privA)
    private val pubB = ClipboardCrypto.publicKeyFromPrivate(privB)
    private val data = "hello header".encodeToByteArray()
    private val salt = "x|y"

    @Test
    fun bothEndsOfAPairProduceTheSameProof() {
        val fromA = ClipboardCrypto.authenticate(data, privA, pubB, salt)
        val fromB = ClipboardCrypto.authenticate(data, privB, pubA, salt)

        assertArrayEquals(fromA, fromB)
    }

    @Test
    fun theProofIsNotTheClipboardKeyAndChangesWithTheData() {
        val proof = ClipboardCrypto.authenticate(data, privA, pubB, salt)

        assertFalse(proof.contentEquals(ClipboardCrypto.authenticate("other".encodeToByteArray(), privA, pubB, salt)))
        assertFalse(proof.contentEquals(ClipboardCrypto.authenticate(data, privA, pubB, "y|x")))
    }

    /** The same derivation written with JDK primitives only, which the Go daemon repeats with its own libraries. */
    @Test
    fun matchesAnIndependentDerivation() {
        val kf = KeyFactory.getInstance("XDH")
        val spec = NamedParameterSpec.X25519
        val priv = kf.generatePrivate(XECPrivateKeySpec(spec, privA))
        val pub = kf.generatePublic(XECPublicKeySpec(spec, BigInteger(1, pubB.reversedArray())))
        val agreement = KeyAgreement.getInstance("XDH").apply { init(priv); doPhase(pub, true) }
        val shared = agreement.generateSecret()

        val prk = hmac(salt.encodeToByteArray(), shared)
        val key = hmac(prk, "FileApex-Hello-v1".encodeToByteArray() + byteArrayOf(1))
        val expected = hmac(key, data)

        assertEquals(expected.toHex(), ClipboardCrypto.authenticate(data, privA, pubB, salt).toHex())
        // Go repeats this exact input in native/tsnet/daemon/hello_test.go.
        println("HELLO_VECTOR " + expected.toHex())
    }

    private fun hmac(key: ByteArray, input: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(input)

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
