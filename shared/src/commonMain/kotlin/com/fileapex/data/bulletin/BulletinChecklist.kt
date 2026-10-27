package com.fileapex.data.bulletin

/** Rules for checklist lines, the same on every device: the newest change to a line wins. */
object BulletinChecklist {
    const val MAX_TEXT = 200
    const val MAX_ITEMS = 100
    private const val MAX_ID = 64

    fun cleanText(text: String): String = text.trim().take(MAX_TEXT)

    /** Newer stamp wins; equal stamps go to the larger device id so every device picks the same one. */
    fun replaces(existing: ChecklistItemEntity?, incoming: ChecklistItemEntity): Boolean {
        if (existing == null) return true
        if (incoming.stamp != existing.stamp) return incoming.stamp > existing.stamp
        if (incoming.copy(updatedAt = 0) == existing.copy(updatedAt = 0)) return false
        return incoming.setBy > existing.setBy
    }

    /** A change made on this device always beats the value it was looking at, even when its clock runs behind. */
    fun localChange(existing: ChecklistItemEntity?, changed: ChecklistItemEntity, setBy: String, nowStamp: Long) =
        changed.copy(stamp = maxOf(nowStamp, (existing?.stamp ?: 0L) + 1), setBy = setBy)

    fun toPayload(item: ChecklistItemEntity) = BulletinChecklistItemPayload(
        item.itemId, item.messageId, item.text, item.done, item.deleted, item.position, item.stamp, item.setBy
    )

    fun fromPayload(payload: BulletinChecklistItemPayload) = ChecklistItemEntity(
        itemId = payload.itemId.take(MAX_ID),
        messageId = payload.messageId.take(MAX_ID),
        text = cleanText(payload.text),
        done = payload.done,
        deleted = payload.deleted,
        position = payload.position,
        stamp = payload.stamp,
        setBy = payload.setBy.take(MAX_ID)
    )
}
