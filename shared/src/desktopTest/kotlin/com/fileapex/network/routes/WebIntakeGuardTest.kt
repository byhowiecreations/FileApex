package com.fileapex.network.routes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebIntakeGuardTest {
    private fun allows(host: String?, origin: String?, remote: String = "192.168.1.9", loopbackOnly: Boolean = false, lanOpen: Boolean = true) =
        WebIntakeGuard.allows(host, origin, remote, loopbackOnly, lanOpen)

    @Test
    fun theSharePageOnTheLanAndTheExtensionAreAccepted() {
        assertTrue(allows("192.168.1.5:8080", null))
        assertTrue(allows("192.168.1.5:8080", "http://192.168.1.5:8080"))
        assertTrue(allows("127.0.0.1:8080", "moz-extension://abc-123", "127.0.0.1"))
        assertTrue(allows("127.0.0.1:8080", "chrome-extension://abc", "127.0.0.1"))
        assertTrue(allows("localhost:8080", null, "127.0.0.1"))
        assertTrue(allows("[::1]:8080", null, "::1"))
    }

    @Test
    fun anotherWebSitePostingToTheLocalServerIsRefused() {
        assertFalse(allows("127.0.0.1:8080", "https://evil.example", "127.0.0.1"))
        assertFalse(allows("127.0.0.1:8080", "http://evil.example", "127.0.0.1"))
        assertFalse(allows("127.0.0.1:8080", "null", "127.0.0.1"))
    }

    @Test
    fun aRebindingNameInTheHostHeaderIsRefusedEvenFromItsOwnOrigin() {
        assertFalse(allows("evil.example:8080", "http://evil.example:8080", "127.0.0.1"))
        assertFalse(allows("evil.example", null, "127.0.0.1"))
        assertFalse(allows(null, null))
    }

    @Test
    fun postingToTheBulletinBoardIsForTheLocalExtensionOnly() {
        assertTrue(allows("127.0.0.1:8080", "moz-extension://abc", "127.0.0.1", loopbackOnly = true))
        assertFalse(allows("192.168.1.5:8080", null, "192.168.1.77", loopbackOnly = true))
    }

    @Test
    fun theLanIsShutOutUnlessPairingIsShowing() {
        assertFalse(allows("192.168.1.5:8080", null, "192.168.1.77", lanOpen = false))
        assertTrue(allows("192.168.1.5:8080", null, "192.168.1.77", lanOpen = true))
        assertTrue(allows("127.0.0.1:8080", null, "127.0.0.1", lanOpen = false))
    }
}
