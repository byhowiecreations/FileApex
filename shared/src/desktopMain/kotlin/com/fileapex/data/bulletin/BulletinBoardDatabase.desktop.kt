package com.fileapex.data.bulletin

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fileapex.platform.DesktopPlatformPaths
import kotlinx.coroutines.Dispatchers

actual class BulletinBoardRoomDbBuilder {
    actual fun builder(): RoomDatabase.Builder<BulletinBoardDatabase> {
        val dbFile = DesktopPlatformPaths.bulletinBoardDatabaseFile()
        dbFile.parentFile?.mkdirs()
        return Room.databaseBuilder<BulletinBoardDatabase>(
            name = dbFile.absolutePath
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
    }
}

fun createBulletinBoardDatabase(): BulletinBoardDatabase {
    return BulletinBoardRoomDbBuilder().builder().build()
}
