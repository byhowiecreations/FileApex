package com.fileapex.drop

import java.util.Base64

/**
 * What the sender hands out: `https://getfileapex.com/drop/#v1.<payload>`. Everything the page needs sits after the hash,
 * which a browser never sends to the web host. The payload holds no IP address and no path.
 */
data class GuestDropLink(
    val endpointId: String,
    val relayUrl: String,
    val dropId: String,
    val secret: String,
    val expiresAtMs: Long,
    val name: String,
    val sizeBytes: Long
) {
    fun toUrl(): String {
        val fields = listOf(endpointId, relayUrl, dropId, secret, expiresAtMs.toString(), sizeBytes.toString(), name)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(fields.joinToString("\n").encodeToByteArray())
    }

    companion object {
        const val PAGE = "https://getfileapex.com/drop/"
        private const val PREFIX = "$PAGE#v1."
        private val HEX64 = Regex("[0-9a-f]{64}")
        private val HEX32 = Regex("[0-9a-f]{32}")
        private const val MAX_LINK = 4096

        /** Null for anything that is not a well-formed drop link. Finds the link inside surrounding text. */
        fun parse(text: String): GuestDropLink? {
            if (text.length > MAX_LINK * 4) return null
            val start = text.indexOf(PREFIX)
            if (start < 0) return null
            val payload = text.substring(start + PREFIX.length).takeWhile { !it.isWhitespace() }
            if (payload.isEmpty() || payload.length > MAX_LINK) return null
            val decoded = try {
                Base64.getUrlDecoder().decode(payload).decodeToString()
            } catch (_: IllegalArgumentException) {
                return null
            }
            val f = decoded.split("\n", limit = 7)
            if (f.size != 7) return null
            if (!HEX64.matches(f[0]) || !f[1].startsWith("https://") || !HEX32.matches(f[2]) || !HEX32.matches(f[3])) return null
            val expires = f[4].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val size = f[5].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            return GuestDropLink(f[0], f[1], f[2], f[3], expires, f[6], size)
        }
    }
}
