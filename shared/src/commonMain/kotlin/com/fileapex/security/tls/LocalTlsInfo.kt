package com.fileapex.security.tls

/** This device's own TLS pin and listener port, published by the platform once the identity is loaded. */
object LocalTlsInfo {
    @Volatile
    var pin: String = ""

    @Volatile
    var port: Int = 0
}
