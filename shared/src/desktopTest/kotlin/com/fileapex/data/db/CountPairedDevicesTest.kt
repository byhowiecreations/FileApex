package com.fileapex.data.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class CountPairedDevicesTest {
    private fun file(vararg sql: String): File {
        val f = File.createTempFile("count", ".db").also { it.deleteOnExit() }
        val c = BundledSQLiteDriver().open(f.absolutePath)
        sql.forEach { c.prepare(it).use { s -> s.step() } }
        c.close()
        return f
    }

    @Test
    fun aCurrentFileCountsItsProfiles() {
        val f = file("CREATE TABLE member_profiles (deviceId TEXT)", "INSERT INTO member_profiles VALUES ('a'), ('b')")
        assertEquals(2, countPairedDevices(f))
    }

    @Test
    fun anOlderFileCountsItsPairedDevices() {
        val f = file("CREATE TABLE paired_devices (deviceId TEXT)", "INSERT INTO paired_devices VALUES ('a'), ('b'), ('c')")
        assertEquals(3, countPairedDevices(f))
    }

    @Test
    fun aFileWithNeitherOrNoFileCountsZero() {
        assertEquals(0, countPairedDevices(file("CREATE TABLE other (x TEXT)")))
        assertEquals(0, countPairedDevices(File("/nonexistent/none.db")))
    }
}
