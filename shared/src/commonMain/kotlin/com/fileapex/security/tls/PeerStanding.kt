package com.fileapex.security.tls

/** What a client key may do on the TLS front, decided from the roster's pins and whether the pairing screen is open. */
sealed interface PeerStanding {
    data class Paired(val deviceId: String) : PeerStanding
    data class Revoked(val deviceId: String) : PeerStanding
    data object PairingOnly : PeerStanding
    data object Rejected : PeerStanding

    companion object {
        /** A removed device is told it was removed, except while pairing is open: then it may pair again like any new key. */
        fun of(known: String?, revoked: String?, allowUnpinned: Boolean): PeerStanding = when {
            known != null -> Paired(known)
            allowUnpinned -> PairingOnly
            revoked != null -> Revoked(revoked)
            else -> Rejected
        }
    }
}
