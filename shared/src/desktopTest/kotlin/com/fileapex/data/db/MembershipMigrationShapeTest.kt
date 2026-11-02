package com.fileapex.data.db

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Room checks a migrated database against its entities when the app opens; this checks the same thing ahead of time. */
class MembershipMigrationShapeTest {
    private val tables = listOf("members", "membership_meta", "membership_chain", "member_profiles", "member_local", "legacy_removed")

    private fun shape(connection: SQLiteConnection, table: String): List<List<String>> =
        connection.prepare("PRAGMA table_info(`$table`)").use { s ->
            val rows = mutableListOf<List<String>>()
            while (s.step()) rows += listOf(s.getText(1), s.getText(2), s.getLong(3).toString(), if (s.isNull(4)) "-" else s.getText(4), s.getLong(5).toString())
            rows
        }

    private fun indexes(connection: SQLiteConnection, table: String): List<String> =
        connection.prepare("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = '$table' AND name LIKE 'index_%' ORDER BY name").use { s ->
            val rows = mutableListOf<String>()
            while (s.step()) rows += s.getText(0)
            rows
        }

    @Test
    fun theMigrationBuildsExactlyTheTablesRoomWouldCreateFromTheEntities() = runBlocking {
        val fresh = File.createTempFile("fresh", ".db").also { it.deleteOnExit() }
        val db = Room.databaseBuilder<FileApexDatabase>(fresh.absolutePath).setDriver(BundledSQLiteDriver()).build()
        db.membershipDao().storedMembers()
        db.close()
        val migrated = File.createTempFile("migrated", ".db").also { it.deleteOnExit() }
        val migratedConnection = BundledSQLiteDriver().open(migrated.absolutePath)
        migratedConnection.prepare(
            "CREATE TABLE `paired_devices` (`deviceId` TEXT NOT NULL, `deviceName` TEXT NOT NULL, `lastKnownIp` TEXT NOT NULL, `port` INTEGER NOT NULL, `publicKeyHash` TEXT NOT NULL, `publicKey` TEXT NOT NULL, `e2eeEnabled` INTEGER NOT NULL, `rootPath` TEXT NOT NULL, `clientVersion` TEXT NOT NULL, `clientVersionCode` INTEGER NOT NULL, `platform` TEXT NOT NULL, `os` TEXT NOT NULL, `deviceMake` TEXT NOT NULL, `deviceModel` TEXT NOT NULL, `supportedProtocolsJson` TEXT NOT NULL, `lastSeenEpochMs` INTEGER NOT NULL, `cardPosX` REAL, `cardPosY` REAL, `cardSortOrder` INTEGER NOT NULL, `cardMenuOrder` TEXT NOT NULL, `tilePosX` REAL, `tilePosY` REAL, `tileSortOrder` INTEGER NOT NULL, `tileMenuOrder` TEXT NOT NULL, `clusterVersion` INTEGER NOT NULL, `isRemoved` INTEGER NOT NULL, `removedAt` INTEGER, `tailnetHostname` TEXT NOT NULL, `tailnetIpv4` TEXT NOT NULL, `tlsPin` TEXT NOT NULL, `tlsPinAlt` TEXT NOT NULL, `tlsPort` INTEGER NOT NULL, `hardwareHash` TEXT NOT NULL, PRIMARY KEY(`deviceId`))"
        ).use { it.step() }
        migratedConnection.prepare("CREATE TABLE `removed_devices` (`deviceId` TEXT NOT NULL, `publicKeyHash` TEXT NOT NULL, `lastKnownIp` TEXT NOT NULL, `port` INTEGER NOT NULL, `removedAtEpochMs` INTEGER NOT NULL, `reason` TEXT NOT NULL, `removedBy` TEXT NOT NULL, PRIMARY KEY(`deviceId`))").use { it.step() }
        MIGRATION_24_25.migrate(migratedConnection)
        MIGRATION_25_26.migrate(migratedConnection)
        MIGRATION_26_27.migrate(migratedConnection)
        MIGRATION_27_28.migrate(migratedConnection)
        val freshConnection = BundledSQLiteDriver().open(fresh.absolutePath)
        for (table in tables) {
            assertEquals("columns of $table", shape(freshConnection, table), shape(migratedConnection, table))
            assertEquals("indexes of $table", indexes(freshConnection, table), indexes(migratedConnection, table))
        }
        freshConnection.close()
        migratedConnection.close()
    }

    /**
     * The app's real path: an existing database at version 24 is opened by Room with the migration, and Room validates
     * every table against its entity right after migrating. A fresh database never goes through that check.
     */
    @Test
    fun roomAcceptsTheMigratedDatabaseWhenItOpensAVersion24File() = runBlocking {
        val file = File.createTempFile("v24", ".db").also { it.deleteOnExit() }
        val seed = Room.databaseBuilder<FileApexDatabase>(file.absolutePath).setDriver(BundledSQLiteDriver()).build()
        seed.membershipDao().storedMembers()
        seed.close()
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        connection.prepare("DROP VIEW IF EXISTS `member_effective`").use { it.step() }
        tables.forEach { connection.prepare("DROP TABLE IF EXISTS `$it`").use { s -> s.step() } }
        Schema26.rewind(connection)
        connection.prepare("PRAGMA user_version = 24").use { it.step() }
        connection.close()
        val db = Room.databaseBuilder<FileApexDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(*rosterSafeMigrations())
            .addCallback(MembershipSchemaCallback())
            .build()
        assertEquals(0, db.membershipDao().storedMembers().size)
        db.close()
    }
}
