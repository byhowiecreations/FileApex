package com.fileapex.data.db

/**
 * Empties every table that holds cluster data: the roster, the Bulletin Board with its flags and checklists, pending
 * transfers and sync bookkeeping. Friends are not part of a cluster and stay. Failures are thrown, never swallowed.
 */
suspend fun clearClusterData(db: FileApexDatabase) {
    db.deviceDao().deleteAllDevices()
    db.deviceDao().deleteAllRemovedDevices()
    db.pendingTransferDao().deleteAll()
    db.messageDao().deleteAllMessages()
    db.tombstoneDao().deleteAllTombstones()
    db.messageFlagDao().deleteAllFlags()
    db.checklistItemDao().deleteAllItems()
    db.outboxDao().deleteAllOutbox()
    db.processedPacketDao().deleteAllPackets()
}
