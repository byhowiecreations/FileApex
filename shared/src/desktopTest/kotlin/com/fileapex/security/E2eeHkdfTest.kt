package com.fileapex.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * HKDF-SHA256 outputs are the keys two paired devices derive independently, so the bytes must never change.
 * Expected values are RFC 5869 appendix A vectors and an independent Python reference (hmac and hashlib).
 */
class E2eeHkdfTest {
    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun range(from: Int, to: Int): ByteArray = ByteArray(to - from + 1) { (from + it).toByte() }

    @Test
    fun rfc5869CaseA1BasicSha256() {
        val okm = E2eePrimitives.hkdfSha256(ByteArray(22) { 0x0b }, range(0x00, 0x0c), range(0xf0, 0xf9), 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.toHex())
    }

    @Test
    fun rfc5869CaseA2LongInputs() {
        val okm = E2eePrimitives.hkdfSha256(range(0x00, 0x4f), range(0x60, 0xaf), range(0xb0, 0xff), 82)
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71cc30c58179ec3e87c14c01d5c1f3434f1d87",
            okm.toHex()
        )
    }

    @Test
    fun rfc5869CaseA3EmptySaltAndInfo() {
        val okm = E2eePrimitives.hkdfSha256(ByteArray(22) { 0x0b }, ByteArray(0), ByteArray(0), 42)
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8", okm.toHex())
    }

    @Test
    fun outputLengthsAroundTheBlockSizeMatchTheReference() {
        val ikm = range(0, 31)
        val salt = "pair-a|pair-b".encodeToByteArray()
        val info = "fileapex-test-info".encodeToByteArray()
        assertEquals("36", E2eePrimitives.hkdfSha256(ikm, salt, info, 1).toHex())
        assertEquals(
            "36669b2dec6ef07e4c1e00b3cbf5fefe0027a3c28e05ea1da9c552b7f0bcf4752e",
            E2eePrimitives.hkdfSha256(ikm, salt, info, 33).toHex()
        )
        assertEquals(
            "36669b2dec6ef07e4c1e00b3cbf5fefe0027a3c28e05ea1da9c552b7f0bcf4752e6008ed0b7908d0c0ebf6a1c89cdff07c68b025ef8a41fd59a4afe4ad2fa5cd",
            E2eePrimitives.hkdfSha256(ikm, salt, info, 64).toHex()
        )
    }

    @Test
    fun theLongestAllowedOutputMatchesTheReference() {
        val okm = E2eePrimitives.hkdfSha256(range(0, 31), "s".encodeToByteArray(), "i".encodeToByteArray(), 255 * 32)
        assertEquals(255 * 32, okm.size)
        assertEquals("84ee2ecd719a90da3e9ff99ae3937d04cd25581f101e5b8bf4b38a422f5b928e", okm.takeLast(32).toByteArray().toHex())
    }

    @Test
    fun anOutputLongerThanTheRfcLimitIsRefusedInsteadOfWrappingTheCounter() {
        assertThrows(IllegalArgumentException::class.java) {
            E2eePrimitives.hkdfSha256(range(0, 31), "s".encodeToByteArray(), "i".encodeToByteArray(), 255 * 32 + 1)
        }
    }

    @Test
    fun theThreeCallerShapesDeriveTheKeysTheReferenceDerives() {
        val ikm = range(0, 31)
        val pairSalt = "fx-aaa|fx-bbb".encodeToByteArray()
        assertEquals(
            "3efaeb1eb5043ece78ad29594951f52b5b549553857d3c4496e8a5c107bca201",
            E2eePrimitives.hkdfSha256(ikm, pairSalt, "FileApex-Clipboard-v1".encodeToByteArray(), 32).toHex()
        )
        assertEquals(
            "830a39fc7f24d5e21ccb236b671a00f7a28aee7661e474b2a4c9f9405de874ed",
            E2eePrimitives.hkdfSha256(ikm, pairSalt, "FileApex-Hello-v1".encodeToByteArray(), 32).toHex()
        )
        assertEquals(
            "b19dbd9b80f43ff9507b8042515c1cb128f2d3312281eaf96ae9e5c8f6b3ccba",
            E2eePrimitives.hkdfSha256(ikm, "google-uid-1".encodeToByteArray(), "FileApex-Diagnostics-v1".encodeToByteArray(), 32).toHex()
        )
    }

    @Test
    fun theCallersInputsAreLeftUntouched() {
        val ikm = range(0, 31)
        val salt = "salt".encodeToByteArray()
        val info = "info".encodeToByteArray()
        E2eePrimitives.hkdfSha256(ikm, salt, info, 32)
        assertArrayEquals(range(0, 31), ikm)
        assertArrayEquals("salt".encodeToByteArray(), salt)
        assertArrayEquals("info".encodeToByteArray(), info)
    }
}
