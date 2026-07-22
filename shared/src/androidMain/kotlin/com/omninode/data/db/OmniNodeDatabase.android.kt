package com.omninode.data.db

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.omninode.data.db.MIGRATION_2_3
import com.omninode.data.db.MIGRATION_3_4
import kotlinx.coroutines.Dispatchers

actual class RoomDbBuilder(private val context: Context) {
    actual fun builder(): RoomDatabase.Builder<OmniNodeDatabase> {
        val dbFile = context.getDatabasePath("omninode.db")
        return Room.databaseBuilder<OmniNodeDatabase>(
            context = context,
            name = dbFile.absolutePath
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
    }
}
