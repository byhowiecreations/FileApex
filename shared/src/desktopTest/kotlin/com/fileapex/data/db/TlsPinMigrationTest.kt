package com.fileapex.data.db

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TlsPinMigrationTest {
    private val pinA = "a".repeat(64)
    private val pinB = "b".repeat(64)
    private val pinC = "c".repeat(64)
    private val pinD = "d".repeat(64)

    /** A version 25 file: the old pin columns hold the pins, and `members` has only what the roster layer wrote so far. */
    private suspend fun version25File(): File {
        val file = File.createTempFile("v25pins", ".db").also { it.deleteOnExit() }
        val seed = Room.databaseBuilder<FileApexDatabase>(file.absolutePath).setDriver(BundledSQLiteDriver()).build()
        seed.membershipDao().storedMembers()
        seed.close()
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        Schema26.rewind(connection)
        Schema26.insertDevice(connection, "adopted", pin = pinA, alt = pinB, tlsPort = 9000)
        Schema26.insertDevice(connection, "fresh", pin = pinC, tlsPort = 8081)
        Schema26.insertDevice(connection, "kicked", pin = pinD, removed = true)
        Schema26.insertDevice(connection, "nopin")
        Schema26.insertDevice(connection, "twin", pin = pinA)
        listOf("members", "member_anchors", "membership_meta", "membership_outbox").forEach { connection.prepare("DROP TABLE IF EXISTS `$it`").use { s -> s.step() } }
        MIGRATION_24_25.migrate(connection)
        // The roster constraints a build of that time added on every open; schema 28 replaced them.
        connection.prepare("CREATE UNIQUE INDEX IF NOT EXISTS members_active_pin ON members(tlsPin) WHERE state = 'ACTIVE'").use { it.step() }
        connection.prepare(
            "INSERT INTO members VALUES ('adopted', 'ACTIVE', 'k', '$pinA', 5, 'mac', 'mk', 'paired', 'sig')"
        ).use { it.step() }
        connection.prepare(
            "INSERT INTO members VALUES ('kicked', 'REMOVED', '', '', 6, 'mac', 'mk', 'user_removed', 'sig2')"
        ).use { it.step() }
        connection.prepare("PRAGMA user_version = 25").use { it.step() }
        connection.close()
        return file
    }

    @Test
    fun theOldPinColumnsAreCarriedIntoTheRosterOnce() = runBlocking {
        val file = version25File()
        val db = Room.databaseBuilder<FileApexDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(*rosterSafeMigrations())
            .addCallback(MembershipSchemaCallback())
            .build()
        val rows = db.membershipDao().allEffective().associateBy { it.deviceId }
        db.close()
        assertEquals("a signed row keeps its pin and gains the local alt pin and port", listOf(pinA, pinB, 9000), rows.getValue("adopted").let { listOf(it.tlsPin, it.tlsPinAlt, it.tlsPort) })
        assertEquals("a signed row is not touched otherwise", "paired", rows.getValue("adopted").eventReason)
        assertEquals("a paired device without a row gets a legacy one", listOf("ACTIVE", pinC, 8081), rows.getValue("fresh").let { listOf(it.state, it.tlsPin, it.tlsPort) })
        assertEquals("a removed device keeps its pin as a revoked entry", listOf("REMOVED", pinD), rows.getValue("kicked").let { listOf(it.state, it.tlsPin) })
        assertEquals("a device without a pin has no row", false, rows.containsKey("nopin"))
        assertEquals("a second device with an active pin is skipped", false, rows.containsKey("twin"))
    }
}
