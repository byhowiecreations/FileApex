package com.fileapex.drop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The expected values come from an independent HKDF-SHA256 and the RFC 8032 Ed25519 arithmetic; the browser side checks the same ones. */
class GuestDropKeysTest {
    private val secret = "0123456789abcdef0123456789abcdef"
    private val other = "ffffffffffffffffffffffffffffffff"

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test fun seedsMatchTheIndependentVectors() {
        assertEquals("8d05f6ba357ef5a50cb7ed4e283b78840d1127beca0c52082ff50c0d6c3fca45", hex(GuestDropKeys.seed(secret, 0)))
        assertEquals("f502b0c914e070346b582a6e658db9e372407db92d3249385c12bea2bfbaffc2", hex(GuestDropKeys.seed(secret, 1)))
        assertEquals("701e928b7ef8c3740fdfcb2d8691a5ca0d6e8e38aa527e618a21d6b3e064755b", hex(GuestDropKeys.seed(other, 0)))
    }

    @Test fun endpointIdsMatchTheIndependentVectors() {
        val ids = GuestDropKeys.guestIds(secret)
        assertEquals(
            listOf(
                "e514c51615215bfb30da3cddddfdfc6c8b5b897be4896a037ff706906ff05775",
                "98229a0168d0901ec5b81c6a8f20664c1d534c40c3de0fcf730fcfdbc51348d2"
            ),
            ids
        )
        assertEquals("76579ab6f2c94d339d51b53916f11ee25d36695bd39a1b7cc59138ad89df0389", GuestDropKeys.guestIds(other)[0])
    }

    @Test fun theEd25519PublicKeyMatchesRfc8032Test1() {
        val seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", GuestDropKeys.endpointId(seed))
    }

    @Test fun everyLinkGetsTheAgreedNumberOfDistinctLowercaseIds() {
        val ids = GuestDropKeys.guestIds(secret)
        assertEquals(GuestDropKeys.COUNT, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.matches(Regex("[0-9a-f]{64}")) })
        assertNotEquals(ids, GuestDropKeys.guestIds(other))
    }
}
