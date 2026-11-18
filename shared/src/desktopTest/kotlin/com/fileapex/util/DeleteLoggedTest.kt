package com.fileapex.util

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeleteLoggedTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun captured(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString()
    }

    @Test fun anExistingFileIsRemovedWithoutALog() {
        val file = temp.newFile("a.part")
        val out = captured { file.deleteLogged("removing a part") }
        assertFalse(file.exists())
        assertTrue(out, out.isEmpty())
    }

    @Test fun aMissingFileIsNotAFailure() {
        val out = captured { File(temp.root, "missing.part").deleteLogged("removing a part") }
        assertTrue(out, out.isEmpty())
    }

    @Test fun aFileThatCannotBeRemovedIsLoggedWithTheExceptionClassAndNoPath() {
        val folder = temp.newFolder("full")
        File(folder, "child").writeText("x")
        val out = captured { folder.deleteLogged("removing a part") }
        assertTrue(out, out.contains("removing a part failed - DirectoryNotEmptyException"))
        assertFalse(out, out.contains(temp.root.path))
        assertTrue(folder.exists())
    }
}
