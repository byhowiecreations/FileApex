package com.fileapex.data.db

import androidx.room3.Room
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MembershipTablesTest {
    private fun open(file: File) = Room.databaseBuilder<FileApexDatabase>(file.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .addCallback(MembershipSchemaCallback())
        .build()

    private val pinA = "a".repeat(64)
    private val pinB = "b".repeat(64)

    @Test
    fun migration24to25CreatesTheTablesTheConstraintsAndKeepsExistingRows() = runBlocking {
        val file = File.createTempFile("fileapex-migration", ".db").also { it.deleteOnExit() }
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        connection.prepare("CREATE TABLE `removed_devices` (`deviceId` TEXT NOT NULL, PRIMARY KEY(`deviceId`))").use { it.step() }
        connection.prepare("INSERT INTO `removed_devices` VALUES ('old')").use { it.step() }
        MIGRATION_24_25.migrate(connection)
        connection.prepare("INSERT INTO `members` VALUES ('a', 'ACTIVE', 'k', '${pinA}', 5, 'mac', 'mk', 'paired', 's')").use { it.step() }
        connection.prepare("SELECT COUNT(*) FROM `members`").use { assertTrue(it.step()); assertEquals(1L, it.getLong(0)) }
        connection.prepare("SELECT COUNT(*) FROM `removed_devices`").use { assertTrue(it.step()); assertEquals(1L, it.getLong(0)) }
        connection.close()
    }

    @Test
    fun theDatabaseRefusesAMalformedStateAShortPinAndTwoActiveMembersWithOnePin() = runBlocking {
        val db = open(File.createTempFile("members", ".db").also { it.deleteOnExit() })
        val seed = com.fileapex.di.ChainSeed(db, selfId = "self").start()
        seed.admit("honor", pinA)
        for (bad in listOf<suspend () -> Unit>(
            { db.membershipDao().writeMember("x", "WEIRD", "", "", "") },
            { db.tlsPinDao().putTls("y", "short", "", 8081) },
            { db.tlsPinDao().putTls("y", pinB, "short", 8081) }
        )) {
            try {
                bad()
                fail("the database must refuse it")
            } catch (expected: SQLiteException) {
                assertTrue(expected.message.orEmpty().isNotEmpty())
            }
        }
        db.tlsPinDao().putTls("twin", pinA, "", 8081)
        try {
            db.membershipDao().writeMember("twin", "ACTIVE", "", "", "")
            fail("two active members must not hold one pin")
        } catch (expected: SQLiteException) {
            assertTrue(expected.message.orEmpty().contains("pin"))
        }
        assertEquals(listOf("honor", "self"), db.membershipDao().storedMembers().map { it.deviceId })
        db.close()
    }

    @Test
    fun aMemberWhosePinAnotherActiveMemberHoldsIsNotMadeActiveAndTheRefreshStillWorks() = runBlocking {
        val db = open(File.createTempFile("members", ".db").also { it.deleteOnExit() })
        val seed = com.fileapex.di.ChainSeed(db, selfId = "self").start()
        seed.admit("honor", pinA)
        seed.admit("twin", pinA)
        assertEquals(listOf("honor", "self"), db.membershipDao().storedMembers().map { it.deviceId })
        db.close()
    }

    @Test
    fun aRemovedMemberMayKeepAPinAnActiveMemberNowHolds() = runBlocking {
        val db = open(File.createTempFile("members", ".db").also { it.deleteOnExit() })
        val seed = com.fileapex.di.ChainSeed(db, selfId = "self").start()
        seed.admit("old-honor", pinA)
        seed.remove("old-honor")
        seed.admit("honor", pinA)
        assertEquals(listOf("ACTIVE", "REMOVED"), listOf("honor", "old-honor").map { db.membershipDao().get(it)!!.state })
        db.close()
    }
}
