package com.fileapex.data.db

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fileapex.data.db.seal.DbFieldKeys
import com.fileapex.data.db.seal.SealingPassCallback
import com.fileapex.data.db.seal.SealingSqliteDriver
import com.fileapex.data.db.MIGRATION_2_3
import com.fileapex.data.db.MIGRATION_3_4
import com.fileapex.data.db.MIGRATION_5_6
import com.fileapex.data.db.MIGRATION_6_7
import com.fileapex.data.db.MIGRATION_7_8
import com.fileapex.data.db.MIGRATION_8_9
import com.fileapex.data.db.MIGRATION_9_10
import com.fileapex.data.db.MIGRATION_10_11
import com.fileapex.data.db.MIGRATION_11_12
import com.fileapex.data.bulletin.BulletinBoardMerge
import kotlinx.coroutines.Dispatchers

actual class RoomDbBuilder(private val context: Context) {
    actual fun builder(): RoomDatabase.Builder<FileApexDatabase> {
        val dbFile = context.getDatabasePath("fileapex.db")
        BulletinBoardMerge.backupBeforeMerge(
            dbFile.absolutePath,
            context.getDatabasePath("bulletin_board.db").absolutePath
        )
        return Room.databaseBuilder<FileApexDatabase>(
            context = context,
            name = dbFile.absolutePath
        )
            .setDriver(SealingSqliteDriver(BundledSQLiteDriver(), DbFieldKeys::sealer))
            .addCallback(SealingPassCallback(DbFieldKeys::sealer))
            .addCallback(MembershipSchemaCallback())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(*rosterSafeMigrations())
    }
}
