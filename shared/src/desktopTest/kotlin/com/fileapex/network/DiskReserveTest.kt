package com.fileapex.network

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiskReserveTest {
    @Test
    fun aSmallFileFitsAndOneLargerThanTheVolumeDoesNot() {
        val dir = Files.createTempDirectory("fileapex-reserve").toFile()
        try {
            val target = java.io.File(dir, "new/deeper/file.bin").absolutePath
            assertTrue(SocketFileStreamer.hasRoomFor(target, 1024))
            assertFalse(SocketFileStreamer.hasRoomFor(target, Long.MAX_VALUE / 2))
        } finally {
            dir.deleteRecursively()
        }
    }
}
