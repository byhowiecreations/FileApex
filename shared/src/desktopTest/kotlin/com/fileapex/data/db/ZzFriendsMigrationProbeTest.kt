package com.fileapex.data.db

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

class ZzFriendsMigrationProbeTest {
    @Test
    fun probe() = runBlocking {
        val dir = System.getenv("REAL_DIR")
        assumeTrue(dir != null)
        val tmp = File.createTempFile("bkprobe", "").also { it.delete() }.also { it.mkdirs() }
        File(dir!!).listFiles()!!.forEach { it.copyTo(File(tmp, it.name), overwrite = true) }
        val db = Room.databaseBuilder<FileApexDatabase>(File(tmp, "fileapex.db").absolutePath).setDriver(BundledSQLiteDriver())
            .addMigrations(*rosterSafeMigrations()).addCallback(MembershipSchemaCallback()).build()
        println("PROBE friend rows after 26 to 28 migration: ${db.friendsDao().observeLinks().first().size}")
        db.close()
    }
}
