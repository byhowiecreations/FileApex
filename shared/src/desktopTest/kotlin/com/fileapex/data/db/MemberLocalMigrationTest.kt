package com.fileapex.data.db

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Schema 27 to 28: `members` keeps membership and identity; how this device reaches a member moves to `member_local`. */
class MemberLocalMigrationTest {
    private val p1 = "1".repeat(64)
    private val p2 = "2".repeat(64)
    private val p2b = "b".repeat(64)
    private val p3 = "3".repeat(64)
    private val p4 = "4".repeat(64)
    private val p5 = "5".repeat(64)

    private fun run(connection: SQLiteConnection, sql: String) = connection.prepare(sql).use { it.step() }

    private fun rows(connection: SQLiteConnection, sql: String): List<List<String>> = connection.prepare(sql).use { s ->
        val out = mutableListOf<List<String>>()
        while (s.step()) out += (0 until s.getColumnCount()).map { if (s.isNull(it)) "null" else s.getText(it) }
        out
    }

    private fun member(c: SQLiteConnection, id: String, state: String, key: String, pin: String, actor: String, reason: String, alt: String = "", port: Int = 8081, hash: String = "") =
        run(c, "INSERT INTO members VALUES ('$id', '$state', '$key', '$pin', '$actor', '$reason', '$alt', $port, '$hash')")

    private fun chain(c: SQLiteConnection, n: Int, kind: String, subject: String) =
        run(c, "INSERT INTO membership_chain VALUES ('h$n', $n, 'p$n', '$kind', 'self', 'ak', '$subject', 'sk', '', 'paired', 's')")

    private fun profile(c: SQLiteConnection, id: String) =
        run(c, "INSERT INTO member_profiles VALUES ('$id', 'name-$id', '10.0.0.5', 8080, '', '', 0, '/', '', 0, '', '', '', '', '[]', 0, NULL, NULL, 0, '', NULL, NULL, 0, '', '', '', '')")

    /** The file the build before schema 28 left: a keyed member, one with a revoked pin, key-less ones on and off the chain, a pre-chain removal. */
    private fun schema27(file: File): SQLiteConnection {
        val seed = Room.databaseBuilder<FileApexDatabase>(file.absolutePath).setDriver(BundledSQLiteDriver()).build()
        runBlocking { seed.membershipDao().storedMembers() }
        seed.close()
        val c = BundledSQLiteDriver().open(file.absolutePath)
        Schema27.rewind(c)
        member(c, "self", "ACTIVE", "ks", p1, "", "", hash = "h1")
        member(c, "peer", "ACTIVE", "kp", p2, "", "", alt = p2b)
        member(c, "oldnode", "ACTIVE", "", p3, "", "legacy")
        member(c, "chainold", "ACTIVE", "", p4, "", "legacy")
        member(c, "gone", "REMOVED", "", p5, "self", "user_removed", hash = "hgone")
        member(c, "ghost", "REMOVED", "", "", "", "legacy", port = 0, hash = "hg")
        chain(c, 1, "GENESIS", "self")
        chain(c, 2, "ADMIT", "peer")
        chain(c, 3, "ADMIT", "chainold")
        chain(c, 4, "ADMIT", "gone")
        chain(c, 5, "REMOVE", "gone")
        listOf("peer", "gone", "oldnode").forEach { profile(c, it) }
        return c
    }

    @Test
    fun theMembershipTableKeepsOnlyMembershipAndIdentityAndEveryRowSurvives() {
        val file = File.createTempFile("v27", ".db").also { it.deleteOnExit() }
        val c = schema27(file)
        runBlocking { MIGRATION_27_28.migrate(c) }
        assertEquals(listOf("deviceId", "state", "publicKey", "eventActor", "eventReason"), rows(c, "SELECT name FROM pragma_table_info('members')").map { it[0] })
        assertEquals(
            listOf(
                listOf("chainold", "ACTIVE", "", "", "legacy"), listOf("ghost", "REMOVED", "", "", "legacy"), listOf("gone", "REMOVED", "", "self", "user_removed"),
                listOf("oldnode", "ACTIVE", "", "", "legacy"), listOf("peer", "ACTIVE", "kp", "", ""), listOf("self", "ACTIVE", "ks", "", "")
            ),
            rows(c, "SELECT * FROM members ORDER BY deviceId")
        )
        c.close()
    }

    @Test
    fun everyPinAlternatePortAndKeyHashMovesToTheLocalTableIncludingARevokedPin() {
        val c = schema27(File.createTempFile("v27", ".db").also { it.deleteOnExit() })
        runBlocking { MIGRATION_27_28.migrate(c) }
        assertEquals(
            listOf(
                listOf("chainold", p4, "", "8081", ""), listOf("ghost", "", "", "0", "hg"), listOf("gone", p5, "", "8081", "hgone"),
                listOf("oldnode", p3, "", "8081", ""), listOf("peer", p2, p2b, "8081", ""), listOf("self", p1, "", "8081", "h1")
            ),
            rows(c, "SELECT deviceId, tlsPin, tlsPinAlt, tlsPort, keyHash FROM member_local ORDER BY deviceId")
        )
        c.close()
    }

    @Test
    fun onlyAnActiveMemberWithNoChainRecordIsPendingAndOnlyARemovedOneWithNoChainRecordIsLegacyRemoved() {
        val c = schema27(File.createTempFile("v27", ".db").also { it.deleteOnExit() })
        runBlocking { MIGRATION_27_28.migrate(c) }
        assertEquals(listOf("oldnode"), rows(c, "SELECT deviceId FROM member_local WHERE pendingAdmitAt > 0").map { it[0] })
        assertEquals(listOf(listOf("ghost", "hg", "", "")), rows(c, "SELECT deviceId, keyHash, reason, removedBy FROM legacy_removed"))
        assertEquals(0L, rows(c, "SELECT COUNT(*) FROM member_local WHERE pairedAtEpochMs != 0 OR pairedChainSeq != 0")[0][0].toLong())
        c.close()
    }

    @Test
    fun theViewsReadTheLocalFactsAndTheOldIndexIsGone() {
        val c = schema27(File.createTempFile("v27", ".db").also { it.deleteOnExit() })
        runBlocking { MIGRATION_27_28.migrate(c) }
        assertEquals(
            listOf(listOf("peer", p2, p2b, "8081", "0"), listOf("gone", p5, "", "8081", "1")),
            rows(c, "SELECT deviceId, tlsPin, tlsPinAlt, tlsPort, isRemoved FROM paired_devices_view WHERE deviceId IN ('peer', 'gone') ORDER BY isRemoved")
        )
        assertEquals(
            listOf(listOf("ghost", "hg", ""), listOf("gone", "hgone", "user_removed")),
            rows(c, "SELECT deviceId, publicKeyHash, reason FROM removed_devices_view ORDER BY deviceId")
        )
        assertTrue(rows(c, "SELECT name FROM sqlite_master WHERE name = 'members_active_pin'").isEmpty())
        c.close()
    }

    @Test
    fun roomAcceptsTheMigratedFileAndTheChainsPathFromSchema26Too() = runBlocking {
        val file = File.createTempFile("v27", ".db").also { it.deleteOnExit() }
        schema27(file).close()
        val db = Room.databaseBuilder<FileApexDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(*rosterSafeMigrations())
            .addCallback(MembershipSchemaCallback())
            .build()
        assertEquals(6, db.membershipDao().storedMembers().size)
        assertEquals(1, db.deviceDao().getAllRemovedDevices().count { it.deviceId == "gone" })
        db.close()
    }
}
