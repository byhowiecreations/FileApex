package com.fileapex.update

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** An update may only replace the app with one signed by the same identity, unless the installed app has no identity to keep. */
class MacUpdateIdentityGuardTest {
    private val adHocA = """designated => cdhash H"aaaa""""
    private val adHocB = """designated => cdhash H"bbbb""""
    private val ours = """designated => identifier "com.fileapex" and certificate root = H"6563d236""""
    private val other = """designated => identifier "com.fileapex" and certificate root = H"deadbeef""""

    private fun allowed(installed: String, incoming: String): Boolean {
        val script = macUpdateIdentityGuardShell() + "\nidentity_allowed \"\$1\" \"\$2\""
        val process = ProcessBuilder("bash", "-c", script, "guard", installed, incoming).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        return process.exitValue() == 0
    }

    @Test fun theSameIdentityMayUpdate() = assertTrue(allowed(ours, ours))

    @Test fun anotherIdentityIsRefused() = assertEquals(false, allowed(ours, other))

    @Test fun anAdHocBuildCannotReplaceASignedApp() = assertEquals(false, allowed(ours, adHocA))

    @Test fun aSignedBuildMayReplaceAnAdHocOneBecauseThereWasNothingToKeep() = assertTrue(allowed(adHocA, ours))

    @Test fun anAdHocBuildMayReplaceAnotherAdHocBuild() = assertTrue(allowed(adHocA, adHocB))

    @Test fun anInstalledAppWithNoReadableIdentityHasNothingToKeep() = assertTrue(allowed("", ours))

    @Test fun anIncomingAppWithNoReadableIdentityIsRefusedWhenOneIsPinned() = assertEquals(false, allowed(ours, ""))
}
