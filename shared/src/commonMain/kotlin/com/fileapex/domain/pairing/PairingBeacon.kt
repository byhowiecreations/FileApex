package com.fileapex.domain.pairing

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Ephemeral LAN advertisement while a host is on the pairing screen.
 * Wire format matches the UDP multicast JSON (device name, IP, port, 6-digit code).
 */
@Serializable
data class PairingBeacon(
    val deviceName: String,
    val ipAddress: String,
    val port: Int,
    val pairingCode: String,
    val timestamp: Long,
    val deviceId: String = "",
    val pinRequired: Boolean = false
) {
    fun toPairingPayload(): PairingPayload = PairingPayloadFactory.create(
        deviceId = deviceId,
        deviceName = deviceName,
        host = ipAddress,
        port = port,
        rootPath = "",
        pinRequired = pinRequired,
        pairingCode = pairingCode
    )

    fun matchesCode(input: String): Boolean {
        val digits = digitsOnly(input)
        return digits.length == 6 && digits == pairingCode
    }

    companion object {
        const val WIRE_PREFIX = "FA_PAIR:"
        const val MULTICAST_ADDRESS = "239.255.0.89"
        const val PORT = 8891
        const val HOST_TTL_MS = 60_000L
        /** Re-broadcast until paired or [HOST_TTL_MS] expires (host ticker stops transport). */
        const val BROADCAST_INTERVAL_MS = 250L
        const val BEACON_STALE_MS = 1_500L
        const val MULTICAST_TTL = 1

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun digitsOnly(input: String): String = input.filter { it.isDigit() }

        fun encodePacket(beacon: PairingBeacon): ByteArray =
            (WIRE_PREFIX + json.encodeToString(serializer(), beacon)).encodeToByteArray()

        fun parsePacket(raw: String): PairingBeacon? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            val jsonBody = when {
                text.startsWith(WIRE_PREFIX) -> text.removePrefix(WIRE_PREFIX)
                text.startsWith("{") -> text
                else -> return null
            }
            val beacon = runCatching {
                json.decodeFromString(serializer(), jsonBody)
            }.getOrNull() ?: return null
            return beacon.takeIf { it.isWellFormed() }
        }

        private fun PairingBeacon.isWellFormed(): Boolean {
            if (deviceId.isBlank() || deviceName.isBlank()) return false
            if (ipAddress.isBlank() || port !in 1..65535) return false
            return pairingCode.length == 6 && pairingCode.all { it.isDigit() }
        }
    }
}
