package com.fileapex.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Runs `MIGRATION_26_27` on a populated schema-26 file built from the exported schema, as a real device would have it. */
class ProfileMigrationTest {
    private lateinit var file: File
    private lateinit var connection: SQLiteConnection

    @Before
    fun setUp() {
        file = File.createTempFile("v26", ".db").also { it.deleteOnExit() }
        connection = BundledSQLiteDriver().open(file.absolutePath)
        val schema = Json.parseToJsonElement(File("schemas/com.fileapex.data.db.FileApexDatabase/26.json").readText()).jsonObject
        val entities = schema.getValue("database").jsonObject.getValue("entities").jsonArray
        for (entity in entities.map { it.jsonObject }) {
            val table = entity.getValue("tableName").jsonPrimitive.content
            run(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            val indices = entity["indices"] as? JsonArray ?: JsonArray(emptyList())
            for (index in indices) run(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
        }
    }

    @After
    fun close() = connection.close()

    private fun run(sql: String) = connection.prepare(sql).use { it.step() }

    private fun count(sql: String): Long = connection.prepare(sql).use { it.step(); it.getLong(0) }

    private fun text(sql: String): String = connection.prepare(sql).use { if (it.step()) it.getText(0) else "" }

    private fun columns(table: String): List<String> = connection.prepare("PRAGMA table_info(`$table`)").use { s ->
        val names = mutableListOf<String>()
        while (s.step()) names += s.getText(1)
        names
    }

    private fun metaKeys(): List<String> = connection.prepare("SELECT key FROM membership_meta").use { s ->
        val keys = mutableListOf<String>()
        while (s.step()) keys += s.getText(0)
        keys
    }

    private fun device(id: String, name: String, removed: Int, ip: String = "192.168.1.5") = run(
        "INSERT INTO paired_devices VALUES ('$id', '$name', '$ip', 8080, 'hash-$id', 'key-$id', 1, '/root', '0.19.9', 227, 'android', 'android', 'Honor', 'Magic V5', '[1]', 1234, 10.5, 20.5, 3, 'a,b', NULL, NULL, 4, 'c', 77, $removed, ${if (removed == 1) "88" else "NULL"}, 'host-$id', '100.64.0.1', '${"a".repeat(64)}', '', 8081, 'hw-$id')"
    )

    private fun migrate() = runBlocking { MIGRATION_26_27.migrate(connection) }

    @Test
    fun everyPairedDeviceRowKeepsItsProfileInMemberProfiles() {
        device("active-1", "Honor", removed = 0)
        device("gone-1", "Old phone", removed = 1)
        migrate()
        assertEquals(2L, count("SELECT COUNT(*) FROM member_profiles"))
        assertEquals("Honor", text("SELECT deviceName FROM member_profiles WHERE deviceId = 'active-1'"))
        assertEquals("192.168.1.5", text("SELECT lastKnownIp FROM member_profiles WHERE deviceId = 'active-1'"))
        assertEquals("a,b", text("SELECT cardMenuOrder FROM member_profiles WHERE deviceId = 'active-1'"))
        assertEquals("host-active-1", text("SELECT tailnetHostname FROM member_profiles WHERE deviceId = 'active-1'"))
        assertEquals("hash-gone-1", text("SELECT publicKeyHash FROM member_profiles WHERE deviceId = 'gone-1'"))
    }

    @Test
    fun leftoverClockAndDeadEventColumnsAreGone() {
        device("active-1", "Honor", removed = 0)
        removedDevice("fx-removed", at = 4_000L, reason = "user_removed", by = "fx-mac")
        run("INSERT INTO membership_meta VALUES ('clock', '1')")
        run("INSERT INTO membership_meta VALUES ('clock_check_at', '2')")
        run("INSERT INTO membership_meta VALUES ('clock_check_version', '3')")
        run("INSERT INTO membership_meta VALUES ('chain_handled_removal', 'keep-me')")
        migrate()
        assertEquals(emptyList<String>(), columns("member_profiles").filter { it in setOf("clusterVersion", "eventStamp") })
        assertEquals(emptyList<String>(), columns("members").filter { it in setOf("eventStamp", "eventActorKey", "eventSignature") })
        assertEquals(listOf("eventActor", "eventReason"), columns("members").filter { it in setOf("eventActor", "eventReason") })
        assertEquals(0L, count("SELECT COUNT(*) FROM sqlite_master WHERE name = 'member_anchors'"))
        assertEquals("user_removed", text("SELECT eventReason FROM members WHERE deviceId = 'fx-removed'"))
        assertEquals("fx-mac", text("SELECT eventActor FROM members WHERE deviceId = 'fx-removed'"))
        assertEquals(
            emptyList<String>(),
            metaKeys().filter { it in setOf("clock", "clock_check_at", "clock_check_version") }
        )
        assertEquals("keep-me", text("SELECT value FROM membership_meta WHERE key = 'chain_handled_removal'"))
    }

    @Test
    fun theChainTableIsStillCreated() {
        migrate()
        assertEquals(0L, count("SELECT COUNT(*) FROM membership_chain"))
    }

    private fun member(id: String, state: String, pin: String = "", stamp: Long = 5L) = run(
        "INSERT INTO members (deviceId, state, publicKey, tlsPin, eventStamp, eventActor, eventActorKey, eventReason, eventSignature, tlsPinAlt, tlsPort) " +
            "VALUES ('$id', '$state', '', '$pin', $stamp, '', '', 'legacy', '', '', 0)"
    )

    private fun removedDevice(id: String, at: Long, reason: String, by: String) =
        run("INSERT INTO removed_devices VALUES ('$id', 'hash-$id', '192.168.1.9', 8080, $at, '$reason', '$by')")

    @Test
    fun aTombstoneWithoutAPinBecomesARemovedMember() {
        device("gone-1", "Old phone", removed = 1)
        run("UPDATE paired_devices SET tlsPin = '' WHERE deviceId = 'gone-1'")
        migrate()
        assertEquals("REMOVED", text("SELECT state FROM members WHERE deviceId = 'gone-1'"))
    }

    @Test
    fun aRemovedDevicesRowBecomesARemovedMemberWithItsReasonAndActor() {
        removedDevice("fx-removed", at = 4_000L, reason = "user_removed", by = "fx-mac")
        migrate()
        assertEquals("REMOVED", text("SELECT state FROM members WHERE deviceId = 'fx-removed'"))
        assertEquals("user_removed", text("SELECT eventReason FROM members WHERE deviceId = 'fx-removed'"))
        assertEquals("fx-mac", text("SELECT eventActor FROM members WHERE deviceId = 'fx-removed'"))
    }

    @Test
    fun aMemberRowThatAlreadyExistsIsNeverOverwritten() {
        member("fx-both", "ACTIVE", pin = "b".repeat(64), stamp = 9L)
        removedDevice("fx-both", at = 4_000L, reason = "user_removed", by = "fx-mac")
        migrate()
        assertEquals("ACTIVE", text("SELECT state FROM members WHERE deviceId = 'fx-both'"))
        assertEquals("b".repeat(64), text("SELECT tlsPin FROM members WHERE deviceId = 'fx-both'"))
    }

    @Test
    fun anActiveDeviceKeepsItsMemberRowAndGetsNoRemovedOne() {
        device("active-1", "Honor", removed = 0)
        member("active-1", "ACTIVE", pin = "a".repeat(64))
        migrate()
        assertEquals(1L, count("SELECT COUNT(*) FROM members WHERE deviceId = 'active-1'"))
        assertEquals("ACTIVE", text("SELECT state FROM members WHERE deviceId = 'active-1'"))
    }

    @Test
    fun theCandidateTableIsDroppedBecauseCandidatesLiveInMemory() {
        run("INSERT INTO peer_candidates VALUES ('x', 'intro', 'INTRODUCED', 1, 2, 0, 0, '{}')")
        migrate()
        assertEquals(0L, count("SELECT COUNT(*) FROM sqlite_master WHERE name = 'peer_candidates'"))
    }

    @Test
    fun theRemovedDevicesTableIsFoldedIntoMembersAndDropped() {
        removedDevice("fx-gone", at = 4_000L, reason = "user_removed", by = "fx-mac")
        migrate()
        assertEquals("hash-fx-gone", text("SELECT keyHash FROM members WHERE deviceId = 'fx-gone'"))
        assertEquals(0L, count("SELECT COUNT(*) FROM sqlite_master WHERE name = 'removed_devices'"))
    }

    @Test
    fun aTombstoneProfileKeepsItsKeyHashInMembersToo() {
        device("gone-1", "Old phone", removed = 1)
        migrate()
        assertEquals("hash-gone-1", text("SELECT keyHash FROM members WHERE deviceId = 'gone-1'"))
    }
}
