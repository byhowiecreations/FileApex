package com.fileapex.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AddressRedactionTest {
    @Test
    fun ipv4KeepsOnlyTheLastOctetAndLosesThePort() {
        assertEquals(
            "Tailnet peer at .88 did not answer",
            AddressRedaction.redact("Tailnet peer at 100.93.210.88:8080 did not answer")
        )
        assertEquals("peer .218 refused", AddressRedaction.redact("peer 172.16.16.218 refused"))
        assertEquals("bridge at .1", AddressRedaction.redact("bridge at 127.0.0.1:36309"))
    }

    @Test
    fun ipv6IsHidden() {
        assertEquals("dial [ipv6] failed", AddressRedaction.redact("dial 2600:382:7767:fc33:701a:4eff:fe04:8f33 failed"))
        assertEquals("route [ipv6] down", AddressRedaction.redact("route fe80::6d7f:231e:aaf8:befb down"))
    }

    @Test
    fun otherTextIsLeftAlone() {
        val text = "exchange with Moto Signature (40fc26be): ok in 6881 ms, v0.19.0, 10:10:16"
        assertEquals(text, AddressRedaction.redact(text))
    }
}
