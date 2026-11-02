package com.fileapex.data.db

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomPairingMarksTest {
    private fun open(file: File) = Room.databaseBuilder<FileApexDatabase>(file.absolutePath).setDriver(BundledSQLiteDriver()).build()

    @Test
    fun theMarkIsReadBackAfterTheDatabaseIsClosedAndOpenedAgainAndClearsOnUse() = runBlocking {
        val file = File.createTempFile("marks", ".db").also { it.deleteOnExit() }
        val first = open(file)
        assertEquals(0L, RoomPairingMarks(first.membershipDao()).pairedAt())
        RoomPairingMarks(first.membershipDao()).markPaired(12_345L)
        first.close()
        val second = open(file)
        val marks = RoomPairingMarks(second.membershipDao())
        assertEquals(12_345L, marks.pairedAt())
        marks.clear()
        assertEquals(0L, marks.pairedAt())
        second.close()
    }
}
