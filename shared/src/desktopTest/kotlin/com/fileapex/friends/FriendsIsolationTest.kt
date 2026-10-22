package com.fileapex.friends

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fileapex.data.db.MIGRATION_17_18
import com.fileapex.data.db.MIGRATION_18_19
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Friends must stay structurally apart: only the friends package may reach the friends DAO, and it may not reach the cluster. */
class FriendsIsolationTest {
    private val roots = listOf("src/commonMain/kotlin", "src/androidMain/kotlin", "src/desktopMain/kotlin", "src/jvmCommon/kotlin", "src/irohMain/kotlin")
        .map { File(it) }.filter { it.exists() }

    private fun sources() = roots.flatMap { r -> r.walkTopDown().filter { it.extension == "kt" }.map { it to it.relativeTo(r).path }.toList() }

    @Test
    fun onlyTheFriendsPackageReachesTheFriendsDao() {
        val allowedOutside = setOf("com/fileapex/data/db/FileApexDatabase.kt")
        val leaks = sources().filter { (file, rel) ->
            !rel.startsWith("com/fileapex/friends/") && rel !in allowedOutside &&
                file.readText().let { "friendsDao" in it || "FriendsDao" in it || "com.fileapex.friends.data" in it }
        }.map { it.second }
        assertEquals("these files reach friends storage: $leaks", emptyList<String>(), leaks)
    }

    @Test
    fun theFriendsPackageDoesNotReachClusterCode() {
        val banned = listOf(
            "com.fileapex.network.routes", "com.fileapex.data.bulletin", "com.fileapex.domain.device", "com.fileapex.domain.sync",
            "com.fileapex.domain.backup", "com.fileapex.domain.clipboard", "com.fileapex.domain.browse", "DeviceDao", "PairedDeviceEntity",
            "IrohEndpointLink"
        )
        val bad = sources().filter { it.second.startsWith("com/fileapex/friends/") }.flatMap { (file, rel) ->
            file.readLines().filter { it.startsWith("import ") || it.contains("DeviceDao") }.flatMap { line ->
                banned.filter { line.contains(it) }.map { "$rel: $it" }
            }
        }
        assertEquals("friends code reaches cluster code: $bad", emptyList<String>(), bad)
    }

    @Test
    fun migrationCreatesTheThreeTables() {
        val c = BundledSQLiteDriver().open(File.createTempFile("friends", ".db").also { it.deleteOnExit() }.absolutePath)
        kotlinx.coroutines.runBlocking { MIGRATION_17_18.migrate(c) }
        for (t in listOf("friend_links", "friend_invites", "friend_incoming")) c.prepare("SELECT COUNT(*) FROM `$t`").use { assertTrue(it.step()) }
        c.close()
    }

    @Test
    fun migrationDropsBlockedFriendsAndKeepsTheRest() {
        val c = BundledSQLiteDriver().open(File.createTempFile("friends", ".db").also { it.deleteOnExit() }.absolutePath)
        kotlinx.coroutines.runBlocking { MIGRATION_17_18.migrate(c) }
        c.prepare("INSERT INTO friend_links VALUES ('a','Ann','Friends',1,'ann',0)").use { it.step() }
        c.prepare("INSERT INTO friend_links VALUES ('b','Bob','Friends',1,'bob',1)").use { it.step() }
        kotlinx.coroutines.runBlocking { MIGRATION_18_19.migrate(c) }
        c.prepare("SELECT friendKey, autoAccept FROM friend_links").use {
            assertTrue(it.step())
            assertEquals("a", it.getText(0))
            assertEquals(0L, it.getLong(1))
            assertTrue(!it.step())
        }
        c.close()
    }
}
