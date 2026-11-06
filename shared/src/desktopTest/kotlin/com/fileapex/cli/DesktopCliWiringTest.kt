package com.fileapex.cli

import com.fileapex.data.seal.PlainWriteScan
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CLI only works while the app listens for it, and nothing but the app's entry point can start that listener.
 * It once existed, passed its own tests and was never started, so every CLI call said "Open FileApex".
 */
class DesktopCliWiringTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val main = PlainWriteScan.codeOnly(File(root, "composeApp/src/desktopMain/kotlin/com/fileapex/Main.kt").readText())

    @Test fun theDesktopEntryPointStartsTheCliListener() {
        assertTrue("Main.kt never calls CliIpcDaemon.start()", Regex("""CliIpcDaemon\.start\(""").containsMatchIn(main))
    }

    @Test fun theDesktopEntryPointStopsTheCliListenerOnQuit() {
        assertTrue("Main.kt never calls CliIpcDaemon.stop()", Regex("""CliIpcDaemon\.stop\(""").containsMatchIn(main))
    }
}
