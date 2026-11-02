package com.fileapex.security.tls

import java.util.concurrent.CopyOnWriteArrayList

/**
 * A device the cluster removed gets a new TLS key before it can pair again. The platform installs [renew] (it builds and
 * saves the key, then restarts what serves it); [afterRenewal] lets other holders of the identity, such as the native
 * client on the Mac, pick the new one up.
 */
object TlsIdentityRenewal {
    @Volatile
    var renew: suspend () -> Unit = {}

    val afterRenewal = CopyOnWriteArrayList<() -> Unit>()
}
