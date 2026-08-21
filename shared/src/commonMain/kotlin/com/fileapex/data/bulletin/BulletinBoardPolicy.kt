package com.fileapex.data.bulletin

object BulletinContentType {
    const val TEXT = 0
    const val LINK = 1
    const val IMAGE_PREVIEW = 2
    const val FILE_METADATA = 3
}

object BulletinPayloadType {
    const val MESSAGE = 0
    const val TOMBSTONE = 1
    const val ACK = 2
}

object BulletinBoardPolicy {
    const val OUTBOX_TTL_MS = 7L * 24 * 60 * 60 * 1000
    const val RETENTION_HORIZON_MS = 90L * 24 * 60 * 60 * 1000
    const val SYNC_BATCH_LIMIT = 20
    const val IMAGE_PREVIEW_MAX_BYTES = 64 * 1024
    const val LAN_FILE_MAX_BYTES = 8L * 1024 * 1024
    /** First build with /api/v1/bulletin/sync/batch (version.md code=126, v0.8.1a). */
    const val BULLETIN_SYNC_MIN_VERSION_CODE = 126
}
