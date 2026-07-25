package com.fileapex.util

/**
 * Stable identity markers derived from [deviceId].
 * Not signing keys — used for alias matching and blocklist correlation only.
 */
object DeviceIdentityMarkers {
    fun fingerprint(deviceId: String): String {
        var hash = 0xcbf29ce484222325UL
        val prime = 0x100000001b3UL
        for (ch in deviceId) {
            hash = hash xor ch.code.toULong()
            hash *= prime
        }
        return hash.toString(16).padStart(16, '0')
    }
}
