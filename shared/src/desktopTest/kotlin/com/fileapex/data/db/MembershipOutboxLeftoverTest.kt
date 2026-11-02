package com.fileapex.data.db

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Test devices that ran a 0.19.9c build before the outbox was removed still hold that table; a fresh database has none. */
class MembershipOutboxLeftoverTest {
    private fun tables(file: File): Set<String> {
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        val names = connection.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").use { s ->
            val rows = mutableSetOf<String>()
            while (s.step()) rows += s.getText(0)
            rows
        }
        connection.close()
        return names
    }

    @Test
    fun aFreshDatabaseHasNoOutboxTable() = runBlocking {
        val file = File.createTempFile("fresh26", ".db").also { it.deleteOnExit() }
        val db = Room.databaseBuilder<FileApexDatabase>(file.absolutePath).setDriver(BundledSQLiteDriver()).build()
        db.membershipDao().storedMembers()
        db.close()
        assertFalse(tables(file).contains("membership_outbox"))
    }

    @Test
    fun aDatabaseThatStillHasTheOutboxTableOpensAndKeepsItsRoster() = runBlocking {
        val file = File.createTempFile("old26", ".db").also { it.deleteOnExit() }
        val seed = Room.databaseBuilder<FileApexDatabase>(file.absolutePath).setDriver(BundledSQLiteDriver()).build()
        seed.membershipDao().storedMembers()
        seed.close()
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        connection.prepare(
            "CREATE TABLE IF NOT EXISTS `membership_outbox` (`peerId` TEXT NOT NULL, `signature` TEXT NOT NULL, `eventJson` TEXT NOT NULL, " +
                "`enqueuedAt` INTEGER NOT NULL, PRIMARY KEY(`peerId`, `signature`))"
        ).use { it.step() }
        connection.prepare("INSERT INTO membership_outbox VALUES ('docker', 'sig', '{}', 1)").use { it.step() }
        connection.prepare("INSERT INTO members (deviceId, state, publicKey, eventActor, eventReason) VALUES ('mac', 'ACTIVE', 'k', 'mac', 'paired')").use { it.step() }
        connection.close()
        val db = Room.databaseBuilder<FileApexDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(*rosterSafeMigrations())
            .addCallback(MembershipSchemaCallback())
            .build()
        val ids = db.membershipDao().storedMembers().map { it.deviceId }
        db.close()
        assertEquals(listOf("mac"), ids)
    }
}
