package com.fileapex.data.db

import androidx.room3.Room
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Room validates every table after a migration and rejects an index its entities do not declare. Files made before schema 28 hold the roster's partial
 * unique index (schema 28 replaced it with triggers), so every migration (not only those that touch `members`) has to start
 * without it; the callback puts it back when the database opens.
 */
class RosterSafeMigrationsTest {
    private fun indexNames(connection: SQLiteConnection): List<String> =
        connection.prepare("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'members_active_pin'").use { s ->
            val rows = mutableListOf<String>()
            while (s.step()) rows += s.getText(0)
            rows
        }

    @Test
    fun everyMigrationStartsWithoutTheRosterIndexWhateverItDoes() = runBlocking {
        val file = File.createTempFile("extras", ".db").also { it.deleteOnExit() }
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        connection.prepare("CREATE TABLE members (deviceId TEXT NOT NULL, tlsPin TEXT NOT NULL, state TEXT NOT NULL, PRIMARY KEY(deviceId))").use { it.step() }
        connection.prepare("CREATE UNIQUE INDEX members_active_pin ON members(tlsPin) WHERE state = 'ACTIVE'").use { it.step() }
        assertEquals(listOf("members_active_pin"), indexNames(connection))
        var ran = false
        val nothing = object : Migration(30, 31) {
            override suspend fun migrate(connection: SQLiteConnection) { ran = true }
        }
        rosterSafe(nothing).migrate(connection)
        assertTrue(ran)
        assertEquals(emptyList<String>(), indexNames(connection))
        connection.close()
    }

    @Test
    fun theListCoversEveryVersionStepAndKeepsTheirVersions() {
        val steps = rosterSafeMigrations().map { it.startVersion to it.endVersion }
        assertEquals((2..27).map { it to it + 1 }, steps)
    }
}
