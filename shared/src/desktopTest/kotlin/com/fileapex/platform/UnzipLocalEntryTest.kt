package com.fileapex.platform

import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.UserFacingErrors
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UnzipLocalEntryTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("unzip-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun zip(name: String = "bundle.zip", build: (ZipOutputStream) -> Unit): File {
        val file = File(root, name)
        ZipOutputStream(file.outputStream()).use(build)
        return file
    }

    private fun ZipOutputStream.add(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private val limits = ZipExtractLimits(maxEntries = 5, maxTotalBytes = 64 * 1024, maxEntryBytes = 32 * 1024)

    @Test
    fun extractsNestedFilesAndFolders() {
        val archive = zip {
            it.add("a.txt", "alpha".toByteArray())
            it.add("dir/sub/b.txt", "beta".toByteArray())
        }
        val out = File(extractZipSafely(archive, limits, Long.MAX_VALUE))
        assertEquals(File(root, "bundle"), out)
        assertEquals("alpha", File(out, "a.txt").readText())
        assertEquals("beta", File(out, "dir/sub/b.txt").readText())
    }

    @Test
    fun refusesEntryThatEscapesTheFolderAndWritesNothingOutside() {
        val archive = zip { it.add("../evil.txt", "x".toByteArray()) }
        assertThrows(ArchiveRefusedException::class.java) { extractZipSafely(archive, limits, Long.MAX_VALUE) }
        assertFalse(File(root, "evil.txt").exists())
        assertFalse(File(root, "bundle").exists())
    }

    @Test
    fun refusesTooManyEntries() {
        val archive = zip { zip -> repeat(6) { zip.add("f$it.txt", byteArrayOf(1)) } }
        assertThrows(ArchiveRefusedException::class.java) { extractZipSafely(archive, limits, Long.MAX_VALUE) }
        assertFalse(File(root, "bundle").exists())
    }

    @Test
    fun refusesOneEntryOverThePerEntryCap() {
        val archive = zip { it.add("big.bin", ByteArray(40 * 1024) { i -> (i * 31).toByte() }) }
        assertThrows(ArchiveRefusedException::class.java) { extractZipSafely(archive, limits, Long.MAX_VALUE) }
        assertFalse(File(root, "bundle").exists())
    }

    @Test
    fun refusesWhenTheTotalGoesOverTheTotalCap() {
        val archive = zip { zip ->
            repeat(4) { zip.add("f$it.bin", ByteArray(20 * 1024) { i -> (i * 7 + it).toByte() }) }
        }
        assertThrows(ArchiveRefusedException::class.java) { extractZipSafely(archive, limits, Long.MAX_VALUE) }
        assertFalse(File(root, "bundle").exists())
    }

    @Test
    fun refusesAHighRatioBombBeforeItWritesTheWholeEntry() {
        val archive = zip { it.add("zeros.bin", ByteArray(8 * 1024 * 1024)) }
        val bombLimits = ZipExtractLimits(maxEntries = 5, maxTotalBytes = 1L shl 32, maxEntryBytes = 1L shl 32)
        val error = assertThrows(ArchiveRefusedException::class.java) { extractZipSafely(archive, bombLimits, Long.MAX_VALUE) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("ratio"))
        assertFalse(File(root, "bundle").exists())
    }

    @Test
    fun allowsLargeEntriesBelowTheRatioLimit() {
        val random = java.util.Random(7)
        val archive = zip { it.add("noise.bin", ByteArray(2 * 1024 * 1024).also(random::nextBytes)) }
        val roomy = ZipExtractLimits(maxEntries = 5, maxTotalBytes = 1L shl 32, maxEntryBytes = 1L shl 32)
        val out = File(extractZipSafely(archive, roomy, Long.MAX_VALUE))
        assertEquals(2L * 1024 * 1024, File(out, "noise.bin").length())
    }

    @Test
    fun refusesWhenFreeSpaceWouldBeUsedUp() {
        val archive = zip { it.add("a.bin", ByteArray(20 * 1024) { i -> (i * 13).toByte() }) }
        assertThrows(ArchiveRefusedException::class.java) {
            extractZipSafely(archive, limits, ZipExtractLimits.FREE_SPACE_RESERVE_BYTES + 1024)
        }
        assertFalse(File(root, "bundle").exists())
    }

    @Test
    fun neverMergesIntoAnExistingFolderAndLeavesItUntouched() {
        val existing = File(root, "bundle").apply { mkdirs() }
        File(existing, "keep.txt").writeText("mine")
        val archive = zip { it.add("a.txt", "alpha".toByteArray()) }
        val out = File(extractZipSafely(archive, limits, Long.MAX_VALUE))
        assertEquals(File(root, "bundle (2)"), out)
        assertEquals("mine", File(existing, "keep.txt").readText())
        assertFalse(File(existing, "a.txt").exists())
    }

    @Test
    fun aRefusedExtractionRemovesOnlyTheFolderItCreated() {
        val existing = File(root, "bundle").apply { mkdirs() }
        File(existing, "keep.txt").writeText("mine")
        val archive = zip { zip -> repeat(6) { zip.add("f$it.txt", byteArrayOf(1)) } }
        assertThrows(ArchiveRefusedException::class.java) { extractZipSafely(archive, limits, Long.MAX_VALUE) }
        assertEquals("mine", File(existing, "keep.txt").readText())
        assertFalse(File(root, "bundle (2)").exists())
    }

    @Test
    fun refusalIsShownWithItsOwnLocalizedMessage() {
        val error = ArchiveRefusedException("too many entries")
        val shown = UserFacingErrors.message(error, "unable_to_open_folder")
        assertEquals(AppI18n.t("error_archive_refused"), shown)
        assertFalse(shown, shown == "error_archive_refused")
    }

    @Test
    fun theDefaultLimitsMatchTheDecidedValues() {
        val defaults = ZipExtractLimits()
        assertEquals(20_000, defaults.maxEntries)
        assertEquals(4L shl 30, defaults.maxTotalBytes)
        assertEquals(4L shl 30, defaults.maxEntryBytes)
        assertEquals(200, defaults.maxRatio)
        assertEquals(1L shl 20, defaults.ratioCheckFromBytes)
    }
}
