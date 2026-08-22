package com.fileapex.data.bulletin

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection

val BULLETIN_MIGRATION_1_2 = object : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.prepare(
            "ALTER TABLE tombstones ADD COLUMN remotePurge INTEGER NOT NULL DEFAULT 0"
        ).use { statement ->
            statement.step()
        }
    }
}
