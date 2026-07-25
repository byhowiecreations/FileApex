package com.fileapex.domain.pairing

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PairingPayload(
    val v: Int = 1,
    val deviceId: String,
    val deviceName: String,
    val host: String,
    val port: Int,
    val rootPath: String,
    val publicKeyHash: String = "",
    /** When true, the scanner must supply this device's PIN to complete pairing. */
    val pinRequired: Boolean = false
) {
    /**
     * Compact URI for QR codes — omits [rootPath] / [publicKeyHash] (fetched from the broadcaster after scan).
     * Smaller matrix = faster generation and easier phone scanning.
     */
    fun toQrText(): String = buildString {
        append(PAIR_URI_PREFIX)
        append("?v=").append(v)
        append("&id=").append(encodeQueryValue(deviceId))
        append("&n=").append(encodeQueryValue(deviceName))
        append("&h=").append(encodeQueryValue(host))
        append("&p=").append(port)
        if (pinRequired) append("&pin=1")
    }

    companion object {
        private const val PAIR_URI_PREFIX = "fileapex://pair"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun parse(qrText: String): PairingPayload {
            val trimmed = qrText.trim()
            return when {
                isPairUri(trimmed) -> parsePairUri(trimmed)
                trimmed.startsWith("{") -> parseJson(trimmed)
                else -> error(
                    "Not a FileApex pairing code — scan with Camera and tap Open FileApex, " +
                        "or use Scan QR Code inside FileApex"
                )
            }
        }

        private fun isPairUri(text: String): Boolean {
            val scheme = text.substringBefore(':').lowercase()
            if (scheme != "fileapex" && scheme != "apex" && scheme != "omninode") return false
            val host = text.substringAfter("://", missingDelimiterValue = "")
                .substringBefore('?', missingDelimiterValue = "")
                .substringBefore('/', missingDelimiterValue = "")
            return host.equals("pair", ignoreCase = true)
        }

        private fun parsePairUri(uri: String): PairingPayload {
            val query = uri.substringAfter('?', missingDelimiterValue = "")
            if (query.isBlank()) error("Invalid FileApex pairing link")
            val params = query.split('&').mapNotNull { part ->
                if (part.isBlank()) return@mapNotNull null
                val key = part.substringBefore('=')
                val value = decodeQueryValue(part.substringAfter('=', missingDelimiterValue = ""))
                key to value
            }.toMap()

            val deviceId = params["id"]?.takeIf { it.isNotBlank() }
                ?: error("Pairing link missing device id")
            val deviceName = params["n"]?.takeIf { it.isNotBlank() }
                ?: error("Pairing link missing device name")
            val host = params["h"]?.takeIf { it.isNotBlank() }
                ?: error("Pairing link missing host")
            val port = params["p"]?.toIntOrNull()
                ?: error("Pairing link missing port")
            val version = params["v"]?.toIntOrNull() ?: 1
            val pinRequired = params["pin"] == "1" || params["pin"].equals("true", ignoreCase = true)

            return PairingPayload(
                v = version,
                deviceId = deviceId,
                deviceName = deviceName,
                host = host,
                port = port,
                rootPath = "",
                publicKeyHash = "",
                pinRequired = pinRequired
            )
        }

        private fun parseJson(raw: String): PairingPayload =
            json.decodeFromString(serializer(), raw)

        private fun encodeQueryValue(value: String): String = buildString(value.length) {
            value.forEach { c ->
                when (c) {
                    in 'A'..'Z', in 'a'..'z', in '0'..'9', '-', '_', '.', '~' -> append(c)
                    else -> {
                        val bytes = c.toString().encodeToByteArray()
                        bytes.forEach { byte ->
                            append('%')
                            append(byte.toUByte().toString(16).uppercase().padStart(2, '0'))
                        }
                    }
                }
            }
        }

        private fun decodeQueryValue(value: String): String {
            val bytes = ArrayList<Byte>(value.length)
            var i = 0
            while (i < value.length) {
                when (val c = value[i]) {
                    '%' -> {
                        if (i + 2 < value.length) {
                            bytes.add(value.substring(i + 1, i + 3).toInt(16).toByte())
                            i += 2
                        } else {
                            bytes.add(c.code.toByte())
                        }
                    }
                    '+' -> bytes.add(' '.code.toByte())
                    else -> bytes.add(c.code.toByte())
                }
                i++
            }
            return bytes.toByteArray().decodeToString()
        }
    }
}

object PairingPayloadFactory {
    fun create(
        deviceId: String,
        deviceName: String,
        host: String,
        port: Int,
        rootPath: String,
        publicKeyHash: String = "",
        pinRequired: Boolean = false
    ): PairingPayload {
        return PairingPayload(
            deviceId = deviceId,
            deviceName = deviceName,
            host = host,
            port = port,
            rootPath = rootPath,
            publicKeyHash = publicKeyHash,
            pinRequired = pinRequired
        )
    }
}
