package com.fileapex.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalApiFileTest {
    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("local-api").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    @Test
    fun theFileHoldsThePayloadAndOnlyTheOwnerCanReadIt() {
        val file = File(dir, "local-api.json")
        LocalApiFile.publish(file, """{"port":1,"token":"t"}""")
        assertEquals("""{"port":1,"token":"t"}""", file.readText())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file.toPath()))
    }

    @Test
    fun anOlderWiderFileIsReplacedByAnOwnerOnlyOne() {
        val file = File(dir, "local-api.json").also { it.writeText("old"); it.setReadable(true, false) }
        LocalApiFile.publish(file, "new")
        assertEquals("new", file.readText())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file.toPath()))
    }

    @Test
    fun noTemporaryFileIsLeftBehind() {
        LocalApiFile.publish(File(dir, "local-api.json"), "x")
        assertEquals(listOf("local-api.json"), dir.list()!!.toList())
    }

    @Test
    fun theOldHandoffFoldersAndFilesAreRemovedWithTheirContents() {
        File(dir, "send-jobs").mkdirs(); File(dir, "send-jobs/a.json").writeText("paths")
        File(dir, "send-staging/job1").mkdirs(); File(dir, "send-staging/job1/photo.jpg").writeText("copy of a user file")
        File(dir, "bulletin-jobs").mkdirs(); File(dir, "bulletin-jobs/b.json").writeText("note")
        File(dir, "ready-device-ids.json").writeText("[\"d1\"]")
        File(dir, "keep.txt").writeText("keep")
        LegacyHandoffCleanup.run(dir)
        assertEquals(listOf("keep.txt"), dir.list()!!.toList())
    }

    @Test
    fun cleaningAFolderWithoutThemDoesNothing() {
        LegacyHandoffCleanup.run(dir)
        assertTrue(dir.list()!!.isEmpty())
        assertFalse(File(dir, "send-jobs").exists())
    }
}
