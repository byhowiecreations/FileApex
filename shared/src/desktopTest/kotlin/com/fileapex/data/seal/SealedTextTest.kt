package com.fileapex.data.seal

import com.fileapex.data.db.seal.FieldSealer
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SealedTextTest {
    private lateinit var dir: File
    private var sealer: FieldSealer? = FieldSealer(ByteArray(32) { it.toByte() })

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("sealed-text").toFile()
        sealer = FieldSealer(ByteArray(32) { it.toByte() })
        SealedText.sealerProvider = { sealer }
    }

    @After
    fun tearDown() {
        SealedText.sealerProvider = { null }
        dir.deleteRecursively()
    }

    @Test
    fun sealedTextRoundTripsAndHidesThePlainText() {
        val stored = SealedText.seal("labels", "Kitchen Tablet")!!
        assertFalse(stored.contains("Kitchen"))
        assertEquals("Kitchen Tablet", SealedText.open("labels", stored))
    }

    @Test
    fun aValueSealedForOnePurposeDoesNotOpenForAnother() {
        val stored = SealedText.seal("labels", "x")!!
        assertNull(SealedText.open("trash", stored))
    }

    @Test
    fun aLockedKeyGivesNothingToWriteAndNothingToRead() {
        val stored = SealedText.seal("labels", "x")!!
        sealer = null
        assertNull(SealedText.seal("labels", "x"))
        assertNull(SealedText.open("labels", stored))
    }

    @Test
    fun textWrittenPlainByAnOlderVersionIsReturnedAsIs() {
        assertEquals("old plain", SealedText.open("labels", "old plain"))
    }

    @Test
    fun theFileHoldsNoPlainTextAfterAWrite() {
        val file = File(dir, "labels.json")
        assertTrue(SealedText.writeFile(file, "labels", "[\"Kitchen Tablet\"]"))
        assertFalse(file.readText().contains("Kitchen"))
        assertEquals("[\"Kitchen Tablet\"]", SealedText.readFile(file, "labels"))
    }

    @Test
    fun aPlainFileFromAnOlderVersionIsReadAndSealedInPlace() {
        val file = File(dir, "labels.json").also { it.writeText("[\"Kitchen Tablet\"]") }
        assertEquals("[\"Kitchen Tablet\"]", SealedText.readFile(file, "labels"))
        assertFalse("the file was sealed in place", file.readText().contains("Kitchen"))
    }

    @Test
    fun aLockedKeyNeverWritesPlainTextAndKeepsTheOldFile() {
        val file = File(dir, "labels.json")
        assertTrue(SealedText.writeFile(file, "labels", "first"))
        val before = file.readText()
        sealer = null
        assertFalse(SealedText.writeFile(file, "labels", "second"))
        assertEquals(before, file.readText())
        assertEquals("", SealedText.readFile(file, "labels"))
    }

    @Test
    fun aMissingFileReadsAsEmpty() {
        assertEquals("", SealedText.readFile(File(dir, "none"), "labels"))
    }
}
