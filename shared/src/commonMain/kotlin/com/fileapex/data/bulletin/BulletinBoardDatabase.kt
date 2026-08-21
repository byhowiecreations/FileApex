package com.fileapex.data.bulletin

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor

@Database(
    entities = [
        MessageEntity::class,
        TombstoneEntity::class,
        OutboxEntity::class,
        ProcessedPacketEntity::class
    ],
    version = 1
)
@ConstructedBy(BulletinBoardDatabaseConstructor::class)
abstract class BulletinBoardDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun tombstoneDao(): TombstoneDao
    abstract fun outboxDao(): OutboxDao
    abstract fun processedPacketDao(): ProcessedPacketDao
    abstract fun transactionDao(): BulletinBoardTransactionDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object BulletinBoardDatabaseConstructor : RoomDatabaseConstructor<BulletinBoardDatabase> {
    override fun initialize(): BulletinBoardDatabase
}

expect class BulletinBoardRoomDbBuilder {
    fun builder(): RoomDatabase.Builder<BulletinBoardDatabase>
}
