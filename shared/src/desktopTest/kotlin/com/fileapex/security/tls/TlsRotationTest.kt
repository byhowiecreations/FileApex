package com.fileapex.security.tls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsRotationTest {
    private val now = 1_800_000_000_000L
    private val old = TlsIdentityFactory.generate(now)
    private val fresh = TlsIdentityFactory.generate(now)

    private fun proof(issuedAt: Long = now) =
        TlsRotation.sign(old.privateKey, old.certificate.publicKey, fresh.pin, issuedAt)

    @Test
    fun theOldKeyVouchesForTheNewPin() {
        assertTrue(TlsRotation.verify(proof(), setOf(old.pin), fresh.pin, now))
        assertTrue(TlsRotation.verify(TlsRotation.decode(TlsRotation.encode(proof())), setOf("x", old.pin), fresh.pin, now))
    }

    @Test
    fun aProofFromAnyOtherKeyOrForAnyOtherPinIsRefused() {
        val stranger = TlsIdentityFactory.generate(now)
        assertFalse(TlsRotation.verify(proof(), setOf(stranger.pin), fresh.pin, now))
        assertFalse(TlsRotation.verify(proof(), setOf(old.pin), stranger.pin, now))
        val forged = TlsRotation.sign(stranger.privateKey, old.certificate.publicKey, fresh.pin, now)
        assertFalse(TlsRotation.verify(forged, setOf(old.pin), fresh.pin, now))
        assertFalse(TlsRotation.verify(proof().copy(issuedAtEpochMs = now + 1), setOf(old.pin), fresh.pin, now))
        assertFalse(TlsRotation.verify(null, setOf(old.pin), fresh.pin, now))
        assertNull(TlsRotation.decode("not json"))
        assertNull(TlsRotation.decode(""))
    }

    @Test
    fun aProofExpiresAfterNinetyDays() {
        val issued = proof()
        assertTrue(TlsRotation.verify(issued, setOf(old.pin), fresh.pin, now + TlsRotation.MAX_AGE_MS))
        assertFalse(TlsRotation.verify(issued, setOf(old.pin), fresh.pin, now + TlsRotation.MAX_AGE_MS + 1))
    }

    @Test
    fun wordsAreSixStableReadableWordsFromAKnownList() {
        val words = TlsPinWords.wordList()
        assertEquals(64, words.size)
        assertEquals(64, words.toSet().size)
        val pin = "a".repeat(64)
        assertEquals(TlsPinWords.of(pin), TlsPinWords.of(pin.uppercase()))
        assertEquals(TlsPinWords.COUNT, TlsPinWords.of(pin).split("-").size)
        assertNotEquals(TlsPinWords.of(pin), TlsPinWords.of("b".repeat(64)))
    }
}
