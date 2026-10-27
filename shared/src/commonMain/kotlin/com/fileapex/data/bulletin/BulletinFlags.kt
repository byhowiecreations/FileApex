package com.fileapex.data.bulletin

/**
 * Rules for message flags. A flag has one value for the whole cluster; any device may change it. The change with the
 * newest stamp wins everywhere, so a device that was away and still holds an older value cannot undo a newer one.
 */
object BulletinFlags {
    const val LOCKED = "locked"
    private const val MAX_ID = 64
    private const val MAX_KIND = 32

    /** First build that sends and understands flag items (version.md code=219, 0.19.8). */
    const val MIN_VERSION_CODE = 219

    fun id(messageId: String, kind: String): String = "flag:$kind:$messageId"

    fun supportedBy(clientVersionCode: Int): Boolean {
        val code = if (clientVersionCode >= 1_000_000) clientVersionCode % 1000 else clientVersionCode
        return code >= MIN_VERSION_CODE
    }

    /** Newer stamp wins; equal stamps go to the larger device id so every device picks the same one. */
    fun replaces(existing: MessageFlagEntity?, incoming: MessageFlagEntity): Boolean {
        if (existing == null) return true
        if (incoming.stamp != existing.stamp) return incoming.stamp > existing.stamp
        if (incoming.enabled == existing.enabled && incoming.setBy == existing.setBy) return false
        return incoming.setBy > existing.setBy
    }

    /** A change made on this device always beats the value it was looking at, even when its clock runs behind. */
    fun localChange(existing: MessageFlagEntity?, messageId: String, kind: String, enabled: Boolean, setBy: String, nowStamp: Long) =
        MessageFlagEntity(
            flagId = id(messageId, kind),
            messageId = messageId,
            kind = kind,
            enabled = enabled,
            stamp = maxOf(nowStamp, (existing?.stamp ?: 0L) + 1),
            setBy = setBy
        )

    fun toPayload(flag: MessageFlagEntity) = BulletinFlagPayload(flag.messageId, flag.kind, flag.enabled, flag.stamp, flag.setBy)

    /** Peer input: ids and the kind are cut to a sane length before they are stored. */
    fun fromPayload(payload: BulletinFlagPayload): MessageFlagEntity {
        val messageId = payload.messageId.take(MAX_ID)
        val kind = payload.kind.take(MAX_KIND)
        return MessageFlagEntity(id(messageId, kind), messageId, kind, payload.enabled, payload.stamp, payload.setBy.take(MAX_ID))
    }
}
