package com.fileapex.domain.presence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanTransitionFilterTest {
    private val filter = LanTransitionFilter()

    @Test
    fun availableAndLostAreTransitions() {
        assertTrue(filter.onAvailable("wifi-1"))
        assertTrue(filter.onLost("wifi-1"))
    }

    @Test
    fun capabilityUpdatesWithNoValidationChangeAreIgnored() {
        filter.onAvailable("wifi-1")
        assertFalse(filter.onCapabilities("wifi-1", validated = true))

        // Screen on, signal strength and bandwidth updates repeat the same validated state.
        repeat(5) { assertFalse(filter.onCapabilities("wifi-1", validated = true)) }
    }

    @Test
    fun becomingValidatedIsATransition() {
        filter.onAvailable("wifi-1")
        assertFalse(filter.onCapabilities("wifi-1", validated = false))

        assertTrue(filter.onCapabilities("wifi-1", validated = true))
        assertFalse(filter.onCapabilities("wifi-1", validated = true))
    }

    @Test
    fun losingValidationIsNotATransition() {
        filter.onAvailable("wifi-1")
        filter.onCapabilities("wifi-1", validated = true)

        assertFalse(filter.onCapabilities("wifi-1", validated = false))
    }

    @Test
    fun aNewLanAddressIsATransition() {
        filter.onAvailable("wifi-1")
        assertFalse(filter.onLinkAddresses("wifi-1", setOf("172.16.16.87")))
        assertFalse(filter.onLinkAddresses("wifi-1", setOf("172.16.16.87")))

        assertTrue(filter.onLinkAddresses("wifi-1", setOf("172.16.16.99")))
    }

    @Test
    fun aReconnectedNetworkStartsFresh() {
        filter.onAvailable("wifi-1")
        filter.onCapabilities("wifi-1", validated = true)
        filter.onLinkAddresses("wifi-1", setOf("172.16.16.87"))
        filter.onLost("wifi-1")

        assertTrue(filter.onAvailable("wifi-1"))
        assertFalse(filter.onCapabilities("wifi-1", validated = true))
        assertFalse(filter.onLinkAddresses("wifi-1", setOf("192.168.1.20")))
    }
}
