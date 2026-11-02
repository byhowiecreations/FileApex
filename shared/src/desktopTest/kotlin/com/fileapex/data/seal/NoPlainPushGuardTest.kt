package com.fileapex.data.seal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** Data protection part 4: a Bulletin Board push never carries content, a file name or a Drive id in the clear. Only sealed pushes are sent. */
class NoPlainPushGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    @Test
    fun noCodeBuildsOrSendsAPlainNotePush() {
        val offenders = File(root, "shared/src").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/desktopTest/" !in it.path && "/androidUnitTest/" !in it.path }
            .filter { file -> file.readText().let { "sendNoteWake(" in it || "sendNoteDeleteWake(" in it } }
            .map { it.name }.sorted().toList()
        assertEquals("these files still build or send a plain note push", emptyList<String>(), offenders)
    }
}
