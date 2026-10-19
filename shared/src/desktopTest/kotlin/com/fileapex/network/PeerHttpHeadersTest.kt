package com.fileapex.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeerHttpHeadersTest {
    @Test
    fun parsesHeadersIntoLowerCaseKeysAndSkipsTheStatusLine() {
        val block = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nX-FileApex-Membership-Version: 1760000000123\r\nConnection: close"
        val headers = PeerHttpHeaders.parse(block)
        assertEquals("text/plain", headers["content-type"])
        assertEquals("1760000000123", headers["x-fileapex-membership-version"])
        assertNull(headers["http/1.1 200 ok"])
    }

    @Test
    fun valueKeepsColonsAndIsTrimmed() {
        val headers = PeerHttpHeaders.parse("HTTP/1.1 200 OK\r\nX-Test:   a:b:c  ")
        assertEquals("a:b:c", headers["x-test"])
    }

    @Test
    fun membershipVersionIsNullWhenMissingOrNotANumber() {
        assertNull(PeerHttpHeaders.membershipVersion(emptyMap()))
        assertNull(PeerHttpHeaders.membershipVersion(mapOf("x-fileapex-membership-version" to "abc")))
        assertEquals(42L, PeerHttpHeaders.membershipVersion(mapOf("x-fileapex-membership-version" to "42")))
    }
}
