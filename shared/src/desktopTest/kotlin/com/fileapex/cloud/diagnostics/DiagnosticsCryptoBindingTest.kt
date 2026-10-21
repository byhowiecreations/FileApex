package com.fileapex.cloud.diagnostics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsCryptoBindingTest {
    private val a = DiagnosticsCrypto.generateKeyPair()
    private val b = DiagnosticsCrypto.generateKeyPair()
    private val text = "{}".encodeToByteArray()

    @Test
    fun theOlderFormatStillRoundTripsThroughTheEitherReader() {
        val sealed = DiagnosticsCrypto.encrypt(text, a.privateKey, b.publicKey, "uid")
        assertFalse(DiagnosticsCrypto.isBound(sealed))
        val aad = DiagnosticsCrypto.boundData("a", "b", "s1", response = false)
        assertArrayEquals(text, DiagnosticsCrypto.decryptEither(sealed, b.privateKey, a.publicKey, "uid", aad))
    }

    @Test
    fun aBoundMessageOpensOnlyForTheSameSenderRecipientSessionAndDirection() {
        val aad = DiagnosticsCrypto.boundData("a", "b", "s1", response = false)
        val sealed = DiagnosticsCrypto.encryptBound(text, a.privateKey, b.publicKey, "uid", aad)
        assertTrue(DiagnosticsCrypto.isBound(sealed))
        assertArrayEquals(text, DiagnosticsCrypto.decryptEither(sealed, b.privateKey, a.publicKey, "uid", aad))
        fun fails(other: ByteArray) = runCatching {
            DiagnosticsCrypto.decryptEither(sealed, b.privateKey, a.publicKey, "uid", other)
        }.isFailure
        assertTrue(fails(DiagnosticsCrypto.boundData("a", "b", "s2", response = false)))
        assertTrue(fails(DiagnosticsCrypto.boundData("a", "b", "s1", response = true)))
        assertTrue(fails(DiagnosticsCrypto.boundData("b", "a", "s1", response = false)))
    }
}
