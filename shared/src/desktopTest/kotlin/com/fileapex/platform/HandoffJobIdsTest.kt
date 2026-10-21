package com.fileapex.platform

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffJobIdsTest {
    @Test
    fun uuidsAreSafeAndPathSegmentsAreNot() {
        assertTrue(HandoffJobIds.isSafe("3F2504E0-4F89-11D3-9A0C-0305E82C3301"))
        assertFalse(HandoffJobIds.isSafe(""))
        assertFalse(HandoffJobIds.isSafe(".."))
        assertFalse(HandoffJobIds.isSafe("../.."))
        assertFalse(HandoffJobIds.isSafe("a/b"))
        assertFalse(HandoffJobIds.isSafe("a\\b"))
        assertFalse(HandoffJobIds.isSafe("a".repeat(65)))
        assertNull(HandoffJobIds.safeOrNull("../x"))
        assertEquals("abc-1", HandoffJobIds.safeOrNull("abc-1"))
    }

    @Test
    fun deleteStagingOnlyRemovesADirectChild() {
        val parent = Files.createTempDirectory("handoff-ids").toFile()
        try {
            val root = java.io.File(parent, "send-staging").also { it.mkdirs() }
            val job = java.io.File(root, "job1").also { it.mkdirs() }
            java.io.File(job, "f.txt").writeText("x")
            val sibling = java.io.File(parent, "keep").also { it.mkdirs() }

            HandoffJobIds.deleteStaging(root, "../keep")
            HandoffJobIds.deleteStaging(root, "..")
            assertTrue(sibling.isDirectory)
            assertTrue(root.isDirectory)

            HandoffJobIds.deleteStaging(root, "job1")
            assertFalse(job.exists())
            assertTrue(root.isDirectory)
        } finally {
            parent.deleteRecursively()
        }
    }
}
