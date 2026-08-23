package com.fileapex.domain.clipboard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ClipboardCryptoTest {

    @Test
    fun encryptDecryptRoundTrip() {
        val alice = ClipboardCrypto.generateKeyPair()
        val bob = ClipboardCrypto.generateKeyPair()
        val salt = ClipboardCrypto.pairSalt("alice", "bob")
        val plaintext = "copied from phone".encodeToByteArray()
        val cipher = ClipboardCrypto.encrypt(plaintext, alice.privateKey, bob.publicKey, salt)
        val recovered = ClipboardCrypto.decrypt(cipher, bob.privateKey, alice.publicKey, salt)
        assertArrayEquals(plaintext, recovered)
    }

    @Test
    fun ciphertextIsNotPlaintext() {
        val alice = ClipboardCrypto.generateKeyPair()
        val bob = ClipboardCrypto.generateKeyPair()
        val salt = ClipboardCrypto.pairSalt("a", "b")
        val plaintext = "secret-clipboard"
        val cipher = ClipboardCrypto.encrypt(plaintext.encodeToByteArray(), alice.privateKey, bob.publicKey, salt)
        assertNotEquals(plaintext, cipher)
    }
}
