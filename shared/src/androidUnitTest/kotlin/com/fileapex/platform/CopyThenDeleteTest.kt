package com.fileapex.platform

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CopyThenDeleteTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun movesFolderAndRemovesSourceOnlyAfterTheCopyMatches() {
        val source = temp.newFolder("src").apply {
            File(this, "a.txt").writeText("alpha")
            File(this, "sub").mkdir()
            File(this, "sub/b.txt").writeText("beta")
        }
        val dest = File(temp.root, "dest")
        assertTrue(copyThenDelete(source, dest))
        assertFalse(source.exists())
        assertEquals("alpha", File(dest, "a.txt").readText())
        assertEquals("beta", File(dest, "sub/b.txt").readText())
    }

    @Test
    fun movesSingleFile() {
        val source = temp.newFile("one.txt").apply { writeText("x") }
        val dest = File(temp.root, "two.txt")
        assertTrue(copyThenDelete(source, dest))
        assertFalse(source.exists())
        assertEquals("x", dest.readText())
    }

    @Test
    fun failedCopyLeavesSourceUntouchedAndNoPartialDestination() {
        val source = temp.newFile("keep.txt").apply { writeText("data") }
        val dest = File(temp.root, "missing-parent/inner/keep.txt").also { File(temp.root, "missing-parent").writeText("blocker") }
        assertFalse(copyThenDelete(source, dest))
        assertTrue(source.exists())
        assertEquals("data", source.readText())
    }
}
