package com.fileapex.data.bulletin

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

actual class BulletinBoardRoomDbBuilder(private val context: Context) {
    actual fun builder(): RoomDatabase.Builder<BulletinBoardDatabase> {
        val dbFile = context.getDatabasePath("bulletin_board.db")
        return Room.databaseBuilder<BulletinBoardDatabase>(
            context = context,
            name = dbFile.absolutePath
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(BULLETIN_MIGRATION_1_2)
    }
}

fun createBulletinBoardDatabase(context: Context): BulletinBoardDatabase {
    return BulletinBoardRoomDbBuilder(context).builder().build()
}
