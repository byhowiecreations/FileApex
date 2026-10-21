package com.fileapex.domain.clipboard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardCryptoBindingTest {
    private val a = ClipboardCrypto.generateKeyPair()
    private val b = ClipboardCrypto.generateKeyPair()
    private val salt = ClipboardCrypto.pairSalt("a", "b")
    private val text = "https://example.com".encodeToByteArray()

    private fun aad(from: String, to: String, context: String) = "FileApex-E2EE-v2|$from|$to|$context".encodeToByteArray()

    @Test
    fun theOlderFormatStillRoundTrips() {
        val sealed = ClipboardCrypto.encrypt(text, a.privateKey, b.publicKey, salt)
        assertArrayEquals(text, ClipboardCrypto.decrypt(sealed, b.privateKey, a.publicKey, salt))
    }

    @Test
    fun aBoundMessageOpensOnlyForTheSameSenderRecipientAndContext() {
        val sealed = ClipboardCrypto.encrypt(text, a.privateKey, b.publicKey, salt, aad("a", "b", "1000"))
        assertArrayEquals(text, ClipboardCrypto.decrypt(sealed, b.privateKey, a.publicKey, salt, aad("a", "b", "1000")))
        // Replayed under a fresh timestamp, reflected back at its sender, or read as the older format: all refused.
        assertTrue(runCatching { ClipboardCrypto.decrypt(sealed, b.privateKey, a.publicKey, salt, aad("a", "b", "2000")) }.isFailure)
        assertTrue(runCatching { ClipboardCrypto.decrypt(sealed, a.privateKey, b.publicKey, salt, aad("b", "a", "1000")) }.isFailure)
        assertTrue(runCatching { ClipboardCrypto.decrypt(sealed, b.privateKey, a.publicKey, salt) }.isFailure)
    }
}
