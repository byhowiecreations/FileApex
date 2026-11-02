package com.fileapex.network.routes

/** The one place a request may name a different device than its TLS certificate: a device asking to continue under a new id. */
internal object BridgeIdentity {
    const val CHANGE_PATH = "/api/v2/identity/change"
    const val STATUS_PATH = "/api/v2/identity/change/status"

    /** True when the request names nobody, names the certificate's own device, or goes to an identity change route. */
    fun matches(claimed: String, certificateDevice: String, path: String): Boolean {
        if (claimed.isEmpty() || claimed == certificateDevice) return true
        val clean = path.substringBefore('?')
        return clean == CHANGE_PATH || clean == STATUS_PATH
    }
}
