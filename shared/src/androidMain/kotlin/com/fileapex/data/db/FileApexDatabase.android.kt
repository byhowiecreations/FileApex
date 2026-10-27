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
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23)
    }
}
