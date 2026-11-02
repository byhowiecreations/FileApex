package com.fileapex.domain.pairing

/** How a host that is not on pairing protocol 2 answers a joiner that is. */
object PairingRefusal {
    fun hostIsOlder(status: Int, body: String): Boolean = status == 404 || body.contains("pairing_only")
}
