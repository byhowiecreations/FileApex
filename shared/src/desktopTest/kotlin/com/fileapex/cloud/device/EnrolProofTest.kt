package com.fileapex.cloud.device

import com.fileapex.domain.clipboard.ClipboardCrypto
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrolProofTest {
    private fun seed(start: Int) = ByteArray(32) { (start + it).toByte() }

    // Vector made by an independent WebCrypto implementation of the relay side (X25519, HKDF-SHA256, HMAC-SHA256);
    // the Worker's ProofKey.verify accepts exactly this construction.
    @Test
    fun tagMatchesTheRelaysConstruction() {
        val relayPublic = Base64.getDecoder().decode("VxR2nRFr92Q2rnS8eT0sMK0ZA8WaxSc4BcfiaYtBDDY=")
        val devicePublic = ClipboardCrypto.publicKeyFromPrivate(seed(1))
        assertEquals("B6N8vBQgk8i3VdwbEOhstCY3StFqqFPtC9/AsrhtHHw=", Base64.getEncoder().encodeToString(devicePublic))
        val tag = EnrolProof.tag("fx-test", 1790000000L, "nonce-0123456789abcdef") { data ->
            ClipboardCrypto.authenticate(data, seed(1), relayPublic, ClipboardCrypto.pairSalt("fx-test", EnrolProof.RELAY_ID))
        }
        assertEquals("zVQelXw+0Hy4/kp5n0xPDuH9f2M3s5t4zZ1Cb1x6ny0=", tag)
    }

    @Test
    fun nonceIsLongEnoughAndNotRepeated() {
        val a = EnrolProof.newNonce()
        assertTrue(a.length in 16..64)
        assertTrue(a != EnrolProof.newNonce())
    }
}
