package com.fileapex.platform

/** What the macOS extensions read to find the running app: the port, nothing else. */
object LocalApiInfo {
    fun payload(port: Int): String = """{"port":$port}"""
}
