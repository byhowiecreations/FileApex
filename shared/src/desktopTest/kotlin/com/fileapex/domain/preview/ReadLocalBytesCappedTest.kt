package com.fileapex.domain.preview

import java.nio.file.Files
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ReadLocalBytesCappedTest {
    private val dir = Files.createTempDirectory("preview-read").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun fileOf(bytes: ByteArray) = java.io.File(dir, "f.bin").also { it.writeBytes(bytes) }.absolutePath

    @Test
    fun readsTheWholeFileWhenItFitsTheCap() {
        val bytes = Random(1).nextBytes(100_000)
        assertArrayEquals(bytes, FilePreviewManager.readLocalBytesCapped(fileOf(bytes), 200_000))
    }

    @Test
    fun readsAFileExactlyAtTheCap() {
        val bytes = Random(2).nextBytes(8_192 * 3)
        assertArrayEquals(bytes, FilePreviewManager.readLocalBytesCapped(fileOf(bytes), bytes.size.toLong()))
    }

    @Test
    fun refusesAFileOverTheCapWithoutReadingPastIt() {
        val bytes = Random(3).nextBytes(50_000)
        try {
            FilePreviewManager.readLocalBytesCapped(fileOf(bytes), 49_999)
            fail("expected the cap to refuse the file")
        } catch (e: IllegalStateException) {
            assertTrue(e.message != null)
        }
    }

    @Test
    fun refusesAnEmptyFile() {
        try {
            FilePreviewManager.readLocalBytesCapped(fileOf(ByteArray(0)), 1_000)
            fail("expected an empty file to be refused")
        } catch (e: IllegalStateException) {
            assertTrue(e.message != null)
        }
    }
}
