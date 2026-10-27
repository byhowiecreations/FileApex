package com.fileapex.cloud.seal

import com.fileapex.data.db.seal.FieldSealer
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The key that seals the personal fields of this user's cloud device records (name, address, port). Every device of
 * the cluster holds the same one and hands it to its paired peers over the pinned, end-to-end encrypted pin exchange.
 * The higher [epoch] wins (a rotation raises it); for equal epochs the lower [id] wins, so devices that each made a
 * key before they met all settle on the same one.
 */
@Serializable
class ClusterRecordKey(val epoch: Int, val id: String, private val keyBase64: String) {
    fun bytes(): ByteArray = Base64.getDecoder().decode(keyBase64)

    fun sealer(): FieldSealer {
        val raw = bytes()
        return try { FieldSealer(raw) } finally { raw.fill(0) }
    }

    fun beats(other: ClusterRecordKey?): Boolean =
        other == null || epoch > other.epoch || (epoch == other.epoch && id < other.id)

    fun toJson(): String = json.encodeToString(serializer(), this)

    fun isWellFormed(): Boolean = id.isNotBlank() && runCatching { bytes().size == KEY_BYTES }.getOrDefault(false)

    companion object {
        const val KEY_BYTES = 32
        private val json = Json { ignoreUnknownKeys = true }

        fun generate(epoch: Int = 1): ClusterRecordKey {
            val random = SecureRandom()
            val key = ByteArray(KEY_BYTES).also(random::nextBytes)
            val id = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            return ClusterRecordKey(epoch, id, Base64.getEncoder().encodeToString(key)).also { key.fill(0) }
        }

        fun fromJson(text: String): ClusterRecordKey? =
            runCatching { json.decodeFromString(serializer(), text) }.getOrNull()?.takeIf { it.isWellFormed() }
    }
}
