package com.fileapex.domain.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingSasTest {
    private fun code(host: String = "h", joiner: String = "j", a: String = "na", b: String = "nb") =
        PairingSas.code(host, joiner, "host-id", "join-id", a, b)

    @Test
    fun bothEndsShowTheSameSixDigits() {
        assertEquals(code(), code(host = "H", joiner = "J", a = "NA", b = "NB"))
        assertTrue(Regex("\\d{3} \\d{3}").matches(code()))
    }

    @Test
    fun aDifferentKeyOnEitherLegShowsADifferentNumber() {
        assertNotEquals(code(), code(host = "attacker"))
        assertNotEquals(code(), code(joiner = "attacker"))
        assertNotEquals(code(), code(a = "other"))
        assertNotEquals(code(), code(b = "other"))
    }

    @Test
    fun matchesTheDockerNodeVectors() {
        // The same vectors are asserted in native/tsnet/daemon/paircheck_test.go.
        assertEquals("077 390", PairingSas.code("aabbcc", "ddeeff", "host-id", "join-id", "0a1b", "2c3d"))
        assertEquals("914 018", PairingSas.code("ddeeff", "aabbcc", "host-id", "join-id", "0a1b", "2c3d"))
        assertEquals(
            "16d116308cc6901b6ede677d8ec639ab097a9b8d04d709854dc0c163cd370945",
            PairingSas.commit("2c3d", "ddeeff", "join-id")
        )
    }

    @Test
    fun theCommitBindsTheRevealedValueToTheJoinerKeyAndId() {
        val commit = PairingSas.commit("nonce-b", "joinpin", "join-id")
        assertTrue(PairingSas.commitMatches(commit, "nonce-b", "joinpin", "join-id"))
        assertFalse(PairingSas.commitMatches(commit, "nonce-c", "joinpin", "join-id"))
        assertFalse(PairingSas.commitMatches(commit, "nonce-b", "otherpin", "join-id"))
        assertFalse(PairingSas.commitMatches(commit, "nonce-b", "joinpin", "other-id"))
    }
}
