package com.fileapex.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Compose draws inside its own heavyweight layer, so an AWT DropTarget on the ComposePanel never receives a drop (the
 * Windows Drop Files panel accepted nothing). Drops reach Compose content only through the shared Compose modifier.
 */
class DesktopDropBoxDropGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private fun source(name: String) = File(root, "shared/src/desktopMain/kotlin/com/fileapex/platform/$name").readText()

    @Test
    fun theDropBoxFrameInstallsNoAwtDropTarget() {
        val frame = source("DesktopWindowsDropBox.kt")
        assertFalse("an AWT DropTarget on the panel never gets a drop", Regex("\\bDropTarget\\(").containsMatchIn(frame))
    }

    @Test
    fun theDropZoneTakesDropsThroughTheSharedComposeModifier() {
        val content = source("DesktopWindowsDropBoxContent.kt")
        assertTrue(content.contains(".deviceFileDropTarget("))
    }
}
