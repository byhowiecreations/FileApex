package com.fileapex.cloud

import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.clipboard.ClipboardE2ee
import com.fileapex.domain.clipboard.ClipboardShareCoordinator
import com.fileapex.domain.clipboard.PeerE2eeVersions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The parts of a Bulletin Board push that name or contain the user's content. */
@Serializable
data class SealedNoteFields(
    val content: String? = null,
    val driveFileId: String? = null,
    val checksum: String? = null,
    val attachmentName: String? = null,
    val attachmentSize: Long = 0L
)

/**
 * Seals the content of Bulletin Board pushes end to end for one peer, with the same key agreement as the clipboard
 * ([ClipboardE2ee]) and the note id bound in as context. The push then carries only the message type, the note id,
 * the sender and a ciphertext, so neither Google nor the FileApex relay can read a note, a file name or a Drive id.
 */
object NotePushCrypto {
    /** The first version code that reads sealed note pushes; older peers still get the plain fields. */
    const val MIN_VERSION_CODE = 213

    /** Plain pushes carry up to 3000 bytes of text; the ciphertext is larger, and a push may hold about 4000 bytes in all. */
    const val MAX_SEALED_INLINE_BYTES = 1800

    /** A sealed value longer than this would push the whole message past the 4000 bytes the relay allows. */
    const val MAX_SEALED_CHARS = 3300

    private val json = Json { ignoreUnknownKeys = true }

    fun context(noteId: String): String = "note:$noteId"

    fun sealFields(fields: SealedNoteFields, encrypt: (ByteArray) -> String): String =
        encrypt(json.encodeToString(SealedNoteFields.serializer(), fields).encodeToByteArray())

    fun openFields(ciphertext: String, decrypt: (String) -> ByteArray): SealedNoteFields =
        json.decodeFromString(SealedNoteFields.serializer(), decrypt(ciphertext).decodeToString())

    /** The plain data map the receiver's note handlers read, rebuilt from the opened fields. */
    fun merge(data: Map<String, String>, fields: SealedNoteFields): Map<String, String> {
        val merged = data.toMutableMap()
        merged.remove(FcmWakeProtocol.KEY_SEALED)
        fields.content?.let { merged[FcmWakeProtocol.Keys.CONTENT] = it }
        fields.driveFileId?.let { merged[FcmWakeProtocol.Keys.DRIVE_FILE_ID] = it }
        fields.checksum?.let { merged[FcmWakeProtocol.Keys.CHECKSUM] = it }
        fields.attachmentName?.let { merged[FcmWakeProtocol.Keys.ATTACHMENT_NAME] = it }
        if (fields.attachmentSize > 0L) merged[FcmWakeProtocol.Keys.ATTACHMENT_SIZE] = fields.attachmentSize.toString()
        return merged
    }

    /** Ciphertext for [targetDeviceId], or null when that peer cannot read sealed pushes (then the caller sends plain fields). */
    internal suspend fun sealFor(targetDeviceId: String, noteId: String, fields: SealedNoteFields): String? {
        if (!PeerE2eeVersions.supportsSealedNotes(targetDeviceId)) return null
        val device = FileApexServices.deviceRepository.getDevice(targetDeviceId) ?: return null
        val peerKey = ClipboardShareCoordinator.resolvePeerPublicKey(device)
        if (peerKey.isBlank()) return null
        val selfId = loadLocalIdentity().deviceId
        return sealFields(fields) { plain ->
            ClipboardE2ee.encrypt(plain, selfId, targetDeviceId, peerKey, context(noteId))
        }
    }

    /** Seals [fields]; when the ciphertext would not fit in a push the text is left out so the peer pulls it instead. */
    internal suspend fun sealForWithinLimit(targetDeviceId: String, noteId: String, fields: SealedNoteFields): Pair<String, Boolean>? {
        val full = sealFor(targetDeviceId, noteId, fields) ?: return null
        if (full.length <= MAX_SEALED_CHARS || fields.content == null) return full to (fields.content != null)
        val without = sealFor(targetDeviceId, noteId, fields.copy(content = null)) ?: return null
        return without to false
    }

    /** The data map with a sealed push opened, [data] itself for a plain push, or null when a sealed push cannot be opened. */
    suspend fun unseal(data: Map<String, String>): Map<String, String>? {
        val ciphertext = data[FcmWakeProtocol.KEY_SEALED] ?: return data
        val senderId = data[FcmWakeProtocol.KEY_SOURCE_DEVICE_ID].orEmpty().trim()
        val noteId = data[FcmWakeProtocol.KEY_NOTE_ID].orEmpty()
        if (senderId.isEmpty() || noteId.isEmpty()) return null
        return runCatching {
            val key = ClipboardShareCoordinator.verifiedSenderKey(senderId, data[FcmWakeProtocol.KEY_SENDER_PUBLIC_KEY].orEmpty())
            val selfId = loadLocalIdentity().deviceId
            val fields = openFields(ciphertext) { sealed ->
                ClipboardE2ee.decrypt(sealed, selfId, senderId, key, context(noteId))
            }
            merge(data, fields)
        }.onFailure { println("NotePushCrypto: sealed push dropped - ${it.message}") }.getOrNull()
    }
}
