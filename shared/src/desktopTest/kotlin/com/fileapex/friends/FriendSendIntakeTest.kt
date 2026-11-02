package com.fileapex.friends

import com.fileapex.friends.transfer.FriendSendIntake
import com.fileapex.friends.transfer.FriendSendIntake.Drop
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendSendIntakeTest {
    private val dir = Files.createTempDirectory("intake").toFile().also { it.deleteOnExit() }
    private fun file(name: String) = dir.resolve(name).also { it.writeText("x"); it.deleteOnExit() }

    @Test fun aKnownFriendAndRealFilesAreAllAccepted() {
        val a = file("a.txt"); val b = file("b.txt")
        val plan = FriendSendIntake.plan(friendKnown = true, paths = listOf(a.path, b.path))
        assertEquals(listOf(a, b), plan.files)
        assertTrue(plan.drops.isEmpty())
    }

    @Test fun anUnknownFriendDropsEverythingAndSaysWhy() {
        val plan = FriendSendIntake.plan(friendKnown = false, paths = listOf(file("a.txt").path))
        assertTrue(plan.files.isEmpty())
        assertEquals(listOf(Drop.NO_FRIEND), plan.drops)
    }

    @Test fun nothingChosenIsReportedNotSilentlyIgnored() {
        val plan = FriendSendIntake.plan(friendKnown = true, paths = emptyList())
        assertTrue(plan.files.isEmpty())
        assertEquals(listOf(Drop.NOTHING_CHOSEN), plan.drops)
    }

    @Test fun aFolderOrMissingPathIsDroppedButTheRestStillGoes() {
        val a = file("a.txt")
        val plan = FriendSendIntake.plan(friendKnown = true, paths = listOf(a.path, dir.path, dir.resolve("gone").path))
        assertEquals(listOf(a), plan.files)
        assertEquals(listOf(Drop.NOT_A_FILE, Drop.NOT_A_FILE), plan.drops)
    }

    @Test fun theLogLineNamesReasonsAndCountsButNoPath() {
        val plan = FriendSendIntake.plan(friendKnown = true, paths = listOf(dir.resolve("secret-name.txt").path))
        val line = plan.logLine()
        assertTrue(line, line.contains("NOT_A_FILE"))
        assertTrue(line, !line.contains("secret-name"))
    }
}
