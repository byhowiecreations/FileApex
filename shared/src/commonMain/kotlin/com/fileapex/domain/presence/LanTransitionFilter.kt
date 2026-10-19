package com.fileapex.domain.presence

/**
 * Decides which Wi‑Fi/Ethernet callbacks are real LAN transitions. A network appearing or going away,
 * becoming validated, or getting a different IPv4 address counts. Repeated capability updates (screen on,
 * signal strength, bandwidth estimates) do not, so they no longer start a full revalidation.
 */
internal class LanTransitionFilter {
    private val validated = HashMap<String, Boolean>()
    private val addresses = HashMap<String, Set<String>>()

    @Synchronized
    fun onAvailable(network: String): Boolean {
        validated.remove(network)
        addresses.remove(network)
        return true
    }

    @Synchronized
    fun onLost(network: String): Boolean {
        validated.remove(network)
        addresses.remove(network)
        return true
    }

    @Synchronized
    fun onCapabilities(network: String, validated: Boolean): Boolean {
        val previous = this.validated.put(network, validated)
        return previous == false && validated
    }

    @Synchronized
    fun onLinkAddresses(network: String, ipv4: Set<String>): Boolean {
        val previous = addresses.put(network, ipv4)
        return previous != null && previous != ipv4
    }
}
