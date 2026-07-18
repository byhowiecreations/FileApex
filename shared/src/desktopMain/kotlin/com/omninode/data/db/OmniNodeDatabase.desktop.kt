package com.omninode.data.db

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import java.io.File

actual class RoomDbBuilder {
    actual fun builder(): RoomDatabase.Builder<OmniNodeDatabase> {
        val dbFile = File(System.getProperty("user.home"), ".omninode/omninode.db")
        if (!dbFile.parentFile.exists()) {
            dbFile.parentFile.mkdirs()
        }
        return Room.databaseBuilder<OmniNodeDatabase>(
            name = dbFile.absolutePath
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .fallbackToDestructiveMigration(dropAllTables = true)
    }
}
