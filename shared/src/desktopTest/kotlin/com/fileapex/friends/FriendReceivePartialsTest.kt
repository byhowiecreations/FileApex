package com.fileapex.friends

import com.fileapex.friends.transfer.FriendReceiveFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FriendReceivePartialsTest {
    @get:Rule val temp = TemporaryFolder()

    private fun file(name: String, modified: Long) = temp.newFile(name).apply { writeText("x"); setLastModified(modified) }

    @Test fun onlyPartialFilesOlderThanTheCutoffAreDeleted() {
        val oldPart = file(".fileapex-" + "a".repeat(32) + ".part", 1_000L)
        val newPart = file(".fileapex-" + "b".repeat(32) + ".part", 9_000L)
        val oldReceived = file("photo.jpg", 1_000L)
        FriendReceiveFiles.deletePartialsOlderThan(temp.root, 5_000L)
        assertFalse(oldPart.exists())
        assertTrue(newPart.exists())
        assertTrue(oldReceived.exists())
    }

    @Test fun aMissingFolderIsIgnored() {
        FriendReceiveFiles.deletePartialsOlderThan(temp.root.resolve("absent"), 5_000L)
    }
}
