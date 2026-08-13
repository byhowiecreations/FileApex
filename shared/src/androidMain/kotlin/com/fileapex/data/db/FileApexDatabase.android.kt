package com.fileapex.data.db

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fileapex.data.db.MIGRATION_2_3
import com.fileapex.data.db.MIGRATION_3_4
import com.fileapex.data.db.MIGRATION_5_6
import com.fileapex.data.db.MIGRATION_6_7
import com.fileapex.data.db.MIGRATION_7_8
import com.fileapex.data.db.MIGRATION_8_9
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
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
    }
}
