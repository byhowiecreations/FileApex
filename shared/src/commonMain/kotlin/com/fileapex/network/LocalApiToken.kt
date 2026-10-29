package com.fileapex.network

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The secret the running app hands to its own Finder extensions through `local-api.json`. It is new on every launch and
 * lives only in memory and in that one owner-only file. It keeps other apps from driving the local share routes by
 * accident; a process running as the same user that can read the file can still use it, which only a signed
 * keychain group could prevent.
 */
object LocalApiToken {
    const val HEADER = "X-FileApex-Local-Token"

    val current: String by lazy {
        ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    }

    fun matches(candidate: String?): Boolean {
        if (candidate.isNullOrEmpty()) return false
        return MessageDigest.isEqual(candidate.encodeToByteArray(), current.encodeToByteArray())
    }
}
