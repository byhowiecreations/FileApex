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
    val sizeBytes: Long,
    /** 0 for a link made before guest keys existed; otherwise how many derived guest keys the page may choose from (see [GuestDropKeys]). */
    val guestKeys: Int = 0
) {
    fun toUrl(): String {
        val fields = if (guestKeys > 0) {
            listOf(endpointId, relayUrl, dropId, secret, expiresAtMs.toString(), sizeBytes.toString(), guestKeys.toString(), name)
        } else {
            listOf(endpointId, relayUrl, dropId, secret, expiresAtMs.toString(), sizeBytes.toString(), name)
        }
        val prefix = if (guestKeys > 0) PREFIX_V2 else PREFIX_V1
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(fields.joinToString("\n").encodeToByteArray())
    }

    companion object {
        const val PAGE = "https://getfileapex.com/drop/"
        private const val PREFIX_V1 = "$PAGE#v1."
        private const val PREFIX_V2 = "$PAGE#v2."
        private const val MAX_GUEST_KEYS = 4
        private val HEX64 = Regex("[0-9a-f]{64}")
        private val HEX32 = Regex("[0-9a-f]{32}")
        private const val MAX_LINK = 4096

        /** Null for anything that is not a well-formed drop link. Finds the link inside surrounding text. */
        fun parse(text: String): GuestDropLink? {
            if (text.length > MAX_LINK * 4) return null
            val second = text.indexOf(PREFIX_V2)
            val first = text.indexOf(PREFIX_V1)
            val keyed = second >= 0 && (first < 0 || second < first)
            val start = if (keyed) second else first
            if (start < 0) return null
            val payload = text.substring(start + (if (keyed) PREFIX_V2 else PREFIX_V1).length).takeWhile { !it.isWhitespace() }
            if (payload.isEmpty() || payload.length > MAX_LINK) return null
            val decoded = try {
                Base64.getUrlDecoder().decode(payload).decodeToString()
            } catch (_: IllegalArgumentException) {
                return null
            }
            val fieldCount = if (keyed) 8 else 7
            val f = decoded.split("\n", limit = fieldCount)
            if (f.size != fieldCount) return null
            if (!HEX64.matches(f[0]) || !f[1].startsWith("https://") || !HEX32.matches(f[2]) || !HEX32.matches(f[3])) return null
            val expires = f[4].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val size = f[5].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            if (!keyed) return GuestDropLink(f[0], f[1], f[2], f[3], expires, f[6], size)
            val guestKeys = f[6].toIntOrNull()?.takeIf { it in 1..MAX_GUEST_KEYS } ?: return null
            return GuestDropLink(f[0], f[1], f[2], f[3], expires, f[7], size, guestKeys)
        }
    }
}
