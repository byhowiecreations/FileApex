package com.fileapex.data.db.seal

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.security.SecureRandom
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The device profiles moved from `paired_devices` to `member_profiles`: what was sealed for the old table must still open. */
class SealedProfilesTest {
    private val sealer = FieldSealer(ByteArray(32).also { SecureRandom().nextBytes(it) })
    private lateinit var file: File
    private lateinit var raw: SQLiteConnection
    private lateinit var sealing: SQLiteConnection

    @Before
    fun setUp() {
        file = File.createTempFile("profiles", ".db").also { it.deleteOnExit() }
        val driver = BundledSQLiteDriver()
        raw = driver.open(file.absolutePath)
        raw.prepare("CREATE TABLE member_profiles (deviceId TEXT NOT NULL PRIMARY KEY, deviceName TEXT NOT NULL)").use { it.step() }
        raw.prepare("CREATE VIEW paired_devices_view AS SELECT deviceId, deviceName FROM member_profiles").use { it.step() }
        raw.prepare("CREATE TABLE staging (deviceId TEXT NOT NULL, deviceName TEXT NOT NULL)").use { it.step() }
        sealing = SealingSqliteDriver(driver, { sealer }).open(file.absolutePath)
    }

    @After
    fun close() {
        sealing.close()
        raw.close()
    }

    private fun rawName(): String = raw.prepare("SELECT deviceName FROM member_profiles").use { it.step(); it.getText(0) }

    private fun firstText(connection: SQLiteConnection, sql: String): String = connection.prepare(sql).use { it.step(); it.getText(0) }

    @Test
    fun aNameSealedForThePairedDevicesTableIsReadFromMemberProfiles() {
        val stored = sealer.seal("paired_devices", "deviceName", "Honor")
        raw.prepare("INSERT INTO member_profiles VALUES ('d1', '$stored')").use { it.step() }
        assertEquals("Honor", firstText(sealing, "SELECT deviceName FROM member_profiles"))
    }

    @Test
    fun aNameWrittenThroughMemberProfilesIsStoredUnderTheOldContext() {
        sealing.prepare("INSERT INTO member_profiles (deviceId, deviceName) VALUES (?, ?)").use { s ->
            s.bindText(1, "d1"); s.bindText(2, "Honor"); s.step()
        }
        val stored = rawName()
        assertTrue(stored.startsWith(FieldSealer.PREFIX))
        assertEquals("Honor", sealer.open("paired_devices", "deviceName", stored))
    }

    @Test
    fun theViewIsReadWithTheSameContext() {
        val stored = sealer.seal("paired_devices", "deviceName", "Honor")
        raw.prepare("INSERT INTO member_profiles VALUES ('d1', '$stored')").use { it.step() }
        assertEquals("Honor", firstText(sealing, "SELECT deviceName FROM paired_devices_view WHERE deviceId = 'd1'"))
    }

    @Test
    fun anInsertThatSelectsFromAnotherTableIsAllowedAndKeepsTheStoredBytes() {
        val stored = sealer.seal("paired_devices", "deviceName", "Honor")
        raw.prepare("INSERT INTO staging VALUES ('d1', '$stored')").use { it.step() }
        sealing.prepare("INSERT OR IGNORE INTO member_profiles SELECT deviceId, deviceName FROM staging").use { it.step() }
        assertEquals(stored, rawName())
        assertEquals("Honor", firstText(sealing, "SELECT deviceName FROM member_profiles"))
    }
}
