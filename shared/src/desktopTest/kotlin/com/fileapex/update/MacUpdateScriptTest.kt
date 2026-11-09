package com.fileapex.update

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test

/** The generated update script is valid shell and checks the signing identity before it copies anything over the app. */
class MacUpdateScriptTest {
    private fun script(): String {
        val method = PlatformUpdateInstaller::class.java.getDeclaredMethod("buildMacUpdateScript", String::class.java, String::class.java)
        method.isAccessible = true
        return method.invoke(PlatformUpdateInstaller, "/tmp/x.dmg", "/Applications/FileApex.app") as String
    }

    @Test fun theScriptParsesAsShell() {
        val file = File.createTempFile("update", ".sh").also { it.deleteOnExit(); it.writeText(script()) }
        val process = ProcessBuilder("bash", "-n", file.absolutePath).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue(out, process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0)
    }

    @Test fun theIdentityIsCheckedBeforeEveryCopy() {
        val text = script()
        val check = text.indexOf("identity_allowed \"$(installed_identity")
        assertTrue("identity check missing", check >= 0)
        assertTrue(text.indexOf("ditto") > text.indexOf("verify_app() {"))
        assertTrue(check < text.indexOf("ditto"))
        assertTrue(text.contains("signed by a different identity"))
    }
}
