package com.fileapex.data.bulletin

import kotlinx.serialization.json.Json

/** A file message's content is an optional caption, a newline and the metadata JSON. */
internal object BulletinFileContent {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun isFile(contentType: Int): Boolean =
        contentType == BulletinContentType.FILE_METADATA || contentType == BulletinContentType.IMAGE_PREVIEW

    fun metadataJson(content: String): String {
        val idx = content.indexOf("\n{")
        return if (idx >= 0) content.substring(idx + 1) else content
    }

    fun decode(content: String): BulletinFileMetadata? =
        runCatching { json.decodeFromString<BulletinFileMetadata>(metadataJson(content)) }.getOrNull()

    fun rebuild(captionAndJson: String, meta: BulletinFileMetadata): String {
        val idx = captionAndJson.indexOf("\n{")
        val caption = if (idx >= 0) captionAndJson.substring(0, idx).trim() else ""
        val encoded = json.encodeToString(meta)
        return if (caption.isBlank()) encoded else "$caption\n$encoded"
    }

    /**
     * A peer sends a file message without a path, because its path means nothing here. The copy this device already
     * holds keeps the path it has, so a message coming back from a peer cannot erase where the file is.
     */
    fun keepStoredPath(existing: MessageEntity?, incoming: MessageEntity): String {
        if (existing == null || !isFile(incoming.contentType) || !isFile(existing.contentType)) return incoming.content
        val stored = decode(existing.content)?.localPath?.takeIf { it.isNotBlank() } ?: return incoming.content
        val meta = decode(incoming.content) ?: return incoming.content
        if (!meta.localPath.isNullOrBlank()) return incoming.content
        return rebuild(incoming.content, meta.copy(localPath = stored))
    }
}
