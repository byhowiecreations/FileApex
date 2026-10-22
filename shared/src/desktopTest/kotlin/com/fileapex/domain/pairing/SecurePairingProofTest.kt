package com.fileapex.domain.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurePairingProofTest {
    private val secret = "00112233445566778899aabbccddeeff"
    private val pw = SecurePairingProof.passwordFromSecret(secret)!!

    @Test
    fun bothEndsComputeTheSameProof() {
        val a = SecurePairingProof.macA(pw, "n1", "hostpin", "joinpin", "host", "join")
        assertTrue(SecurePairingProof.equal(a, SecurePairingProof.macA(pw, "n1", "HOSTPIN", "joinpin", "host", "join")))
        val b = SecurePairingProof.macB(pw, "n1", "n2", "hostpin", "joinpin", "host", "join")
        assertTrue(SecurePairingProof.equal(b, SecurePairingProof.macB(pw, "n1", "n2", "hostpin", "joinpin", "host", "join")))
    }

    @Test
    fun aSwappedTlsKeyOnEitherLegBreaksTheProof() {
        val honest = SecurePairingProof.macA(pw, "n1", "hostpin", "joinpin", "host", "join")
        assertNotEquals(honest, SecurePairingProof.macA(pw, "n1", "attackerpin", "joinpin", "host", "join"))
        assertNotEquals(honest, SecurePairingProof.macA(pw, "n1", "hostpin", "attackerpin", "host", "join"))
    }

    @Test
    fun aWrongSecretOrIdOrNonceBreaksTheProof() {
        val honest = SecurePairingProof.macA(pw, "n1", "h", "j", "host", "join")
        val other = SecurePairingProof.passwordFromSecret("ffeeddccbbaa99887766554433221100")!!
        assertNotEquals(honest, SecurePairingProof.macA(other, "n1", "h", "j", "host", "join"))
        assertNotEquals(honest, SecurePairingProof.macA(pw, "n1", "h", "j", "host", "other"))
        assertNotEquals(honest, SecurePairingProof.macA(pw, "n2", "h", "j", "host", "join"))
    }

    @Test
    fun theTwoDirectionsCannotBeReplayedAsEachOther() {
        assertNotEquals(
            SecurePairingProof.macA(pw, "n1", "h", "j", "host", "join"),
            SecurePairingProof.macB(pw, "n1", "", "h", "j", "host", "join")
        )
    }

    @Test
    fun fieldBoundariesAreUnambiguous() {
        assertNotEquals(
            SecurePairingProof.macA(pw, "n1", "ab", "c", "host", "join"),
            SecurePairingProof.macA(pw, "n1", "a", "bc", "host", "join")
        )
    }

    @Test
    fun secretsAreValidated() {
        assertNull(SecurePairingProof.passwordFromSecret("short"))
        assertNull(SecurePairingProof.passwordFromSecret("zz112233445566778899aabbccddeeff"))
        assertEquals(16, pw.size)
        assertEquals(32, SecurePairingProof.newSecretHex().length)
        assertNotEquals(SecurePairingProof.newNonceHex(), SecurePairingProof.newNonceHex())
    }
}
