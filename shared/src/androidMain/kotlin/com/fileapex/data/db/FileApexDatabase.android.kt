package com.fileapex.data.db

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fileapex.data.db.MIGRATION_2_3
import com.fileapex.data.db.MIGRATION_3_4
import kotlinx.coroutines.Dispatchers

actual class RoomDbBuilder(private val context: Context) {
    actual fun builder(): RoomDatabase.Builder<FileApexDatabase> {
        val dbFile = context.getDatabasePath("fileapex.db")
        return Room.databaseBuilder<FileApexDatabase>(
            context = context,
            name = dbFile.absolutePath
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
    }
}
