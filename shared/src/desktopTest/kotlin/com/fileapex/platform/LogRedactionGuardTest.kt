package com.fileapex.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LogRedactionGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    private fun sources() = listOf("shared/src", "composeApp/src").map { File(root, it) }.flatMap { dir ->
        dir.walkTopDown().onEnter { it.name != "build" }.filter { it.isFile && it.extension == "kt" && "Test" !in it.path }.toList()
    }

    @Test
    fun noCodeCallsAndroidLogDirectlyExceptTheRedactingWrapper() {
        val offenders = sources().filter { it.name != "RedactingLog.kt" }.filter { file ->
            file.readLines().any { it.trim() == "import android.util.Log" || Regex("""(?<![\w.])android\.util\.Log\.""").containsMatchIn(it) }
        }.map { it.name }
        assertEquals("these files must use com.fileapex.platform.log.Log", emptyList<String>(), offenders)
    }

    @Test
    fun theWrapperRedactsTheMessageAndTheThrowableText() {
        val wrapper = sources().first { it.name == "RedactingLog.kt" }.readText()
        assertTrue(wrapper.contains("LogRedactor.redact"))
        assertTrue("the throwable text carries the exception message", wrapper.contains("getStackTraceString"))
    }

    @Test
    fun bothAppsInstallTheRedactingStreamsFirstThing() {
        val android = File(root, "composeApp/src/androidMain/kotlin/com/fileapex/FileApexApplication.kt").readText()
        assertTrue(android.indexOf("LogRedaction.install()") in 0 until android.indexOf("override fun onCreate"))
        val desktop = File(root, "composeApp/src/desktopMain/kotlin/com/fileapex/Main.kt").readText()
        val main = desktop.substringAfter("fun main(args: Array<String>) {")
        assertTrue(main.indexOf("LogRedaction.install()") in 0..400)
        val windows = File(root, "shared/src/desktopMain/kotlin/com/fileapex/platform/DesktopWindowsBackdrop.kt").readText()
        assertTrue("the Windows console reattach replaces System.out, so it must install again", windows.substringAfter("System.setErr(").contains("LogRedaction.install()"))
    }

    @Test
    fun logFilesTheAppWritesItselfRedactTheirTextToo() {
        val writers = listOf("MacOsExtensionRegistrar.kt", "DesktopLifecycleLog.kt", "NotificationDebugLog.kt")
        val missing = writers.filter { name -> !sources().first { it.name == name }.readText().contains("LogRedactor.redact(") }
        assertEquals("these file loggers bypass the redacting print stream", emptyList<String>(), missing)
    }
}
