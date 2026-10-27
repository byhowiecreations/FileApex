package com.fileapex.data.bulletin

import com.fileapex.data.db.isDockerNode

object BulletinContentType {
    const val TEXT = 0
    const val LINK = 1
    const val IMAGE_PREVIEW = 2
    const val FILE_METADATA = 3
    const val BATTERY_LOW = 4
    /** A checklist: the content is its title, the lines live in `checklist_items`. */
    const val CHECKLIST = 5
}

object BulletinPayloadType {
    const val MESSAGE = 0
    const val TOMBSTONE = 1
    const val RETRACT_BY_KIND = 3
    /** A flag change (Locked). Builds before 0.19.8 ignore the type and never acknowledge it. */
    const val FLAG = 4
    /** One line of a checklist; ignored by builds before 0.19.8. */
    const val CHECKLIST_ITEM = 5
}

object BulletinBoardPolicy {
    const val OUTBOX_TTL_MS = 7L * 24 * 60 * 60 * 1000
    const val RETENTION_HORIZON_MS = 90L * 24 * 60 * 60 * 1000
    const val SYNC_BATCH_LIMIT = 20
    const val IMAGE_PREVIEW_MAX_BYTES = 64 * 1024
    /** A Docker node is a backup receiver with no Bulletin Board and no routes for it: it is never sent posts or deletes. */
    fun takesPartInBulletin(peer: com.fileapex.data.db.PairedDeviceEntity): Boolean = !peer.isDockerNode()

    /** First build with /api/v1/bulletin/sync/batch (version.md code=126, v0.8.1a). */
    const val BULLETIN_SYNC_MIN_VERSION_CODE = 126
}
