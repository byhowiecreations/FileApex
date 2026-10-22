package com.fileapex.friends

import com.fileapex.util.sha256Hex
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * What the inviter shares as a QR or link. The PIN is NOT inside it: the inviter tells it to the friend another way, so a
 * link seen in a text message is useless alone. [address] is `friendsKey|relay|direct,direct`.
 */
data class FriendInviteLink(
    val inviteId: String,
    val secret: String,
    val address: String,
    val group: String,
    val inviterName: String
) {
    val inviterKey: String get() = address.substringBefore('|')

    fun toUri(): String = "fileapex://friend?v=1&i=$inviteId&s=$secret&a=${enc(address)}&g=${enc(group)}&n=${enc(inviterName)}"

    companion object {
        private val HEX32 = Regex("[0-9a-f]{32}")
        private val HEX64 = Regex("[0-9a-f]{64}")

        /** Null for anything that is not a well-formed friends invite. Tolerates surrounding text and a missing scheme slash. */
        fun parse(text: String): FriendInviteLink? {
            val start = text.indexOf("fileapex:")
            if (start < 0) return null
            val raw = text.substring(start).takeWhile { !it.isWhitespace() }
            val rest = raw.removePrefix("fileapex:").trimStart('/')
            if (!rest.startsWith("friend?")) return null
            val q = rest.removePrefix("friend?").split('&').mapNotNull {
                val i = it.indexOf('='); if (i < 0) null else it.substring(0, i) to dec(it.substring(i + 1))
            }.toMap()
            if (q["v"] != "1") return null
            val id = q["i"]?.takeIf { HEX32.matches(it) } ?: return null
            val secret = q["s"]?.takeIf { HEX32.matches(it) } ?: return null
            val address = q["a"]?.takeIf { it.split("|").size == 3 && HEX64.matches(it.substringBefore('|')) } ?: return null
            return FriendInviteLink(id, secret, address, (q["g"] ?: "Friends").take(40), (q["n"] ?: "").take(40))
        }

        private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
        private fun dec(v: String) = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault("")
    }
}

object FriendsInviteSecrets {
    private val random = SecureRandom()

    fun newId(): String = hex(16)
    fun newSecret(): String = hex(16)

    /** Six digits, zero padded. */
    fun newPin(): String = random.nextInt(1_000_000).toString().padStart(6, '0')

    /** What the inviter stores instead of the secret and PIN. */
    fun verifier(secret: String, pin: String): String = sha256Hex("fileapex-friend-invite-v1|${secret.lowercase()}|$pin".encodeToByteArray())

    fun matches(stored: String, secret: String, pin: String): Boolean =
        MessageDigest.isEqual(stored.encodeToByteArray(), verifier(secret, pin).encodeToByteArray())

    private fun hex(bytes: Int) = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
}
