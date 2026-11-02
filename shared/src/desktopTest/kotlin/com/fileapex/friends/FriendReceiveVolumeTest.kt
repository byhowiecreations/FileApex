package com.fileapex.friends

import com.fileapex.friends.transfer.FriendReceiveFiles
import java.io.File
import java.nio.file.FileStore
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Android refuses `Files.getFileStore` with a SecurityException, which must not stop a phone from receiving. */
class FriendReceiveVolumeTest {
    @get:Rule val temp = TemporaryFolder()
    private val refused: (Path) -> FileStore = { throw SecurityException("EACCES") }

    @Test fun aPlatformThatRefusesTheFileStoreStillReservesSpace() {
        val part = File(temp.root, "a.part")
        assertTrue(FriendReceiveFiles.reserve(part, 10L, { 1_000_000_000L }, refused))
        FriendReceiveFiles.release(part)
    }

    @Test fun anyOtherFileStoreFailureAlsoFallsBackInsteadOfDroppingTheTransfer() {
        listOf<(Path) -> FileStore>({ throw UnsupportedOperationException("no store") }, { throw java.io.IOException("no store") }).forEach { failing ->
            val part = File(temp.root, "b.part")
            assertTrue(FriendReceiveFiles.reserve(part, 10L, { 1_000_000_000L }, failing))
            FriendReceiveFiles.release(part)
        }
    }

    @Test fun reservationsOnAnUnknownVolumeStillShareTheSameFreeSpace() {
        val first = File(temp.root, "one.part")
        val second = File(temp.root, "two.part")
        val free = { 100L * 1024 * 1024 + 60L * 1024 * 1024 }
        try {
            assertTrue(FriendReceiveFiles.reserve(first, 80L * 1024 * 1024, free, refused))
            assertFalse("the second file must not count the same space twice", FriendReceiveFiles.reserve(second, 80L * 1024 * 1024, free, refused))
        } finally { FriendReceiveFiles.release(first); FriendReceiveFiles.release(second) }
    }
}
