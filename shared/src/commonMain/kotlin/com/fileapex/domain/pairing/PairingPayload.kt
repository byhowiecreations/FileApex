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

        private val PAIR_SCHEMES = setOf("fileapex", "apex", "omninode")
        private val PAIR_URI_IN_TEXT = Regex(
            """(?i)(fileapex|apex|omninode):/+/?pair/?\?[^\s]+"""
        )
        private val CONTROL_CHARS = Regex("""[\u0000-\u001F\u007F]""")

        fun parse(qrText: String): PairingPayload =
            parseOrNull(qrText)
                ?: throw IllegalArgumentException(parseFailureMessage(qrText))

        /** Returns the first successful parse across [candidates] (OEM scanners disagree on which field is authoritative). */
        fun parseFirstOrNull(candidates: Iterable<String>): PairingPayload? =
            candidates.asSequence()
                .sortedByDescending { looksLikePairingCandidate(it) }
                .mapNotNull { parseOrNull(it) }
                .firstOrNull()

        fun parseOrNull(qrText: String): PairingPayload? = runCatching { parseNormalized(qrText) }.getOrNull()

        fun looksLikePairingCandidate(raw: String): Int {
            val text = normalizeScannedText(raw)
            var score = 0
            if (PAIR_URI_IN_TEXT.containsMatchIn(text)) score += 4
            if (text.substringBefore(':').lowercase() in PAIR_SCHEMES) score += 3
            if (text.contains("id=", ignoreCase = true)) score += 2
            if (text.contains("p=", ignoreCase = true)) score += 1
            if (text.startsWith("pair?", ignoreCase = true)) score += 2
            return score
        }

        fun parseFailureMessage(candidates: Iterable<String>): String {
            val preview = candidates
                .maxByOrNull { looksLikePairingCandidate(it) }
                ?.let { normalizeScannedText(it).replace(CONTROL_CHARS, "").take(56) }
                ?.ifBlank { null }
                ?: "(empty)"
            return "Not a FileApex pairing code — scan with Camera and tap Open FileApex, " +
                "or use Scan QR Code inside FileApex. Scanner saw: $preview"
        }

        fun parseFailureMessage(raw: String): String = parseFailureMessage(listOf(raw))

        private fun parseNormalized(qrText: String): PairingPayload {
            val normalized = normalizeScannedText(qrText)
            return when {
                isPairUri(normalized) -> parsePairUri(extractPairUri(normalized))
                normalized.startsWith("{") -> parseJson(normalized)
                looksLikePairQuery(normalized) -> parsePairUri(queryOnlyToPairUri(normalized))
                else -> error("unrecognized")
            }
        }

        /** Strips scanner noise (BOM, control chars, URL:/URI: prefixes, surrounding whitespace). */
        internal fun normalizeScannedText(raw: String): String {
            var text = raw
                .replace("\u0000", "")
                .replace("\r", "")
                .replace("\n", "")
                .replace("&amp;", "&")
                .replace("&#38;", "&")
                .trim()
                .trimStart('\uFEFF')
            while (text.startsWith("URL:", ignoreCase = true) || text.startsWith("URI:", ignoreCase = true)) {
                text = text.substringAfter(':', missingDelimiterValue = text).trim()
            }
            text = stripHttpWrapper(text)
            PAIR_URI_IN_TEXT.find(text)?.let { return it.value.trim() }
            return text
        }

        private fun stripHttpWrapper(text: String): String {
            val lower = text.lowercase()
            for (prefix in listOf("https://", "http://")) {
                if (!lower.startsWith(prefix)) continue
                val remainder = text.substring(prefix.length)
                if (remainder.substringBefore(':').lowercase() in PAIR_SCHEMES ||
                    PAIR_URI_IN_TEXT.containsMatchIn(remainder)
                ) {
                    return remainder
                }
            }
            return text
        }

        private fun extractPairUri(text: String): String =
            PAIR_URI_IN_TEXT.find(text)?.value?.trim() ?: text

        private fun looksLikePairQuery(text: String): Boolean {
            if (text.contains("://")) return false
            val query = pairQueryBody(text)
            return query.contains("id=") &&
                query.contains("p=") &&
                (query.contains("v=") || query.contains("h=") || query.contains("n="))
        }

        private fun pairQueryBody(text: String): String = when {
            text.startsWith("pair?", ignoreCase = true) -> text.substringAfter('?')
            text.startsWith("pair/", ignoreCase = true) -> text.substringAfter('?').ifBlank {
                text.substringAfter("pair/").substringAfter('?')
            }
            else -> text
        }

        private fun queryOnlyToPairUri(text: String): String =
            "$PAIR_URI_PREFIX?${pairQueryBody(text)}"

        private fun isPairUri(text: String): Boolean {
            if (PAIR_URI_IN_TEXT.containsMatchIn(text)) return true
            val scheme = text.substringBefore(':').lowercase()
            if (scheme !in PAIR_SCHEMES) return false
            return pairAuthority(text.substringAfter(':', missingDelimiterValue = text)) != null
        }

        /** Accepts `//pair`, `///pair`, and `//pair/` before the query. */
        private fun pairAuthority(afterSchemeColon: String): String? {
            var remainder = afterSchemeColon.removePrefix("//").removePrefix("/")
            val authority = remainder.substringBefore('?', missingDelimiterValue = remainder)
                .substringBefore('#', missingDelimiterValue = remainder)
                .trimEnd('/')
            return authority.takeIf { it.equals("pair", ignoreCase = true) }
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
