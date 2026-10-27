package com.fileapex.data.bulletin

/**
 * `updatedAt` is the time this device last changed a row, on its own clock. A peer pulls "everything changed since
 * my cursor", so the stamp must move only when the row really changes: a message that arrives twice is stored
 * once and keeps its stamp. Otherwise two devices would keep re-sending each other the same rows.
 */
object BulletinChange {
    /** The row to write, or null when [existing] already holds the same message. */
    fun messageToStore(existing: MessageEntity?, incoming: MessageEntity, nowMs: Long): MessageEntity? {
        val merged = incoming.copy(content = BulletinFileContent.keepStoredPath(existing, incoming))
        if (existing != null && existing.copy(updatedAt = 0L) == merged.copy(updatedAt = 0L)) return null
        return merged.copy(updatedAt = nowMs)
    }

    /** The tombstone to write, or null when it is already stored. */
    fun tombstoneToStore(existing: TombstoneEntity?, incoming: TombstoneEntity, nowMs: Long): TombstoneEntity? {
        if (existing != null) return null
        return incoming.copy(updatedAt = nowMs)
    }
}
