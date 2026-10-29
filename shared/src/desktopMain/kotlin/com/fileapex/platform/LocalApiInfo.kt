package com.fileapex.platform

import com.fileapex.network.LocalApiToken

/** What the macOS extensions read to find the running app: its port and the token for its local routes. */
object LocalApiInfo {
    fun payload(port: Int, token: String = LocalApiToken.current): String = """{"port":$port,"token":"$token"}"""
}
