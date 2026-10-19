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

val BULLETIN_MIGRATION_2_3 = object : Migration(2, 3) {
    override suspend fun migrate(connection: SQLiteConnection) {
        // Existing rows count as changed when they were created, so a peer with no cursor still gets them all.
        for (sql in listOf(
            "ALTER TABLE messages ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE tombstones ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0",
            "UPDATE messages SET updatedAt = timestamp",
            "UPDATE tombstones SET updatedAt = deletedAt"
        )) {
            connection.prepare(sql).use { statement -> statement.step() }
        }
    }
}
