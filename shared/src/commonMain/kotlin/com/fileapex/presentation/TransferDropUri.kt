package com.fileapex.presentation

/**
 * The text a file drag carries between device tiles and folder views:
 * `fileapex-transfer://<device id or "local">/<path>?name=<name>&size=<bytes>`.
 * Path and name are percent-encoded so `?`, `&`, `#`, `%` and spaces in a file name survive.
 */
internal object TransferDropUri {
    const val SCHEME = "fileapex-transfer://"
    const val LOCAL_SOURCE = "local"

    data class Parsed(val sourceDeviceId: String, val path: String, val name: String, val size: Long) {
        val isLocal: Boolean get() = sourceDeviceId == LOCAL_SOURCE
    }

    fun isTransferUri(text: String): Boolean = text.startsWith(SCHEME)

    fun build(sourceDeviceId: String?, absolutePath: String, name: String, size: Long): String =
        "$SCHEME${sourceDeviceId ?: LOCAL_SOURCE}/${encode(absolutePath, keepSlash = true)}" +
            "?name=${encode(name, keepSlash = false)}&size=$size"

    fun parse(uri: String): Parsed? {
        if (!isTransferUri(uri)) return null
        val withoutScheme = uri.removePrefix(SCHEME)
        val sourceDeviceId = withoutScheme.substringBefore('/')
        val rest = withoutScheme.substringAfter('/', "")
        val path = decode(rest.substringBefore('?'))
        val query = rest.substringAfter('?', "")
        val name = decode(query.substringAfter("name=", "").substringBefore('&'))
            .ifBlank { path.substringAfterLast('/') }
        val size = query.substringAfter("size=", "0").substringBefore('&').toLongOrNull() ?: 0L
        return Parsed(sourceDeviceId, path, name, size)
    }

    private fun encode(text: String, keepSlash: Boolean): String {
        val out = StringBuilder()
        for (byte in text.encodeToByteArray()) {
            val c = byte.toInt() and 0xFF
            val plain = c < 0x80 && (c.toChar().isLetterOrDigit() || c.toChar() in "-._~" || (keepSlash && c.toChar() == '/'))
            if (plain) {
                out.append(c.toChar())
            } else {
                out.append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
            }
        }
        return out.toString()
    }

    private fun decode(text: String): String {
        if ('%' !in text) return text
        val bytes = ArrayList<Byte>(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val hi = if (c == '%' && i + 2 < text.length) hexValue(text[i + 1]) else -1
            val lo = if (hi >= 0) hexValue(text[i + 2]) else -1
            if (hi >= 0 && lo >= 0) {
                bytes.add(((hi shl 4) or lo).toByte())
                i += 3
            } else {
                bytes.addAll(c.toString().encodeToByteArray().toList())
                i += 1
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private const val HEX = "0123456789ABCDEF"
}
