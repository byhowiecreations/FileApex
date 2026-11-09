package com.fileapex.update

import com.fileapex.platform.DesktopPlatformPaths
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The real generated Mac update script run against real signed app bundles and temporary signing identities (two
 * certificates with the same name, like a stolen name but another key), so the identity check is exercised before any
 * release exists. `open` is replaced by a stub: the test never launches an app.
 */
class MacUpdateEndToEndTest {
    private val buildScript = File("../scripts/build-fileapex.sh").absolutePath

    private class Identity(val name: String, val keychain: String)
    private class Run(val code: Int, val out: String)

    private fun temp() = Files.createTempDirectory("fileapex-update").toFile().also { it.deleteOnExit() }

    private fun sh(command: String, env: Map<String, String> = emptyMap()): Run {
        val builder = ProcessBuilder("bash", "-c", command).redirectErrorStream(true)
        builder.environment().putAll(env)
        val process = builder.start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue("timed out: $command", process.waitFor(180, TimeUnit.SECONDS))
        return Run(process.exitValue(), out)
    }

    private fun newIdentity(): Identity {
        val dir = temp()
        val env = mapOf(
            "FILEAPEX_SIGNING_DIR" to dir.absolutePath,
            "FILEAPEX_PIN_FILE" to File(dir, "pin.sha1").absolutePath,
            "FILEAPEX_ENV_FILE" to File(dir, "env").absolutePath,
            "FILEAPEX_DIR" to dir.absolutePath
        )
        val made = sh("FILEAPEX_SOURCE_ONLY=1 source '$buildScript'; setup_signing", env)
        assertEquals(made.out, 0, made.code)
        val name = Regex("FILEAPEX_MAC_SIGN_IDENTITY=\"(.*)\"").find(File(dir, "env").readText())!!.groupValues[1]
        return Identity(name, File(dir, "fileapex-signing.keychain-db").absolutePath)
    }

    private fun makeApp(parent: File, marker: String, bundleId: String = "com.fileapex", identity: Identity?): File {
        val app = File(parent, "FileApex.app")
        File(app, "Contents/MacOS").mkdirs()
        File(app, "Contents/Resources").mkdirs()
        File(app, "Contents/Info.plist").writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>$bundleId</string>
<key>CFBundleExecutable</key><string>FileApex</string>
<key>CFBundlePackageType</key><string>APPL</string>
</dict></plist>
"""
        )
        File(app, "Contents/MacOS/FileApex").also { it.writeText("#!/bin/sh\nexit 0\n"); it.setExecutable(true) }
        File(app, "Contents/Resources/marker.txt").writeText(marker)
        val sign = if (identity == null) "-s -" else "--keychain '${identity.keychain}' -s '${identity.name}'"
        val signed = sh("codesign --force --deep $sign '${app.absolutePath}'")
        assertEquals(signed.out, 0, signed.code)
        return app
    }

    private fun dmgOf(app: File): File {
        val dmg = File(temp(), "update.dmg")
        val made = sh("hdiutil create -quiet -volname FileApexUpdateTest -srcfolder '${app.parentFile.absolutePath}' -ov -format UDZO '${dmg.absolutePath}'")
        assertEquals(made.out, 0, made.code)
        return dmg
    }

    private fun marker(app: File) = File(app, "Contents/Resources/marker.txt").readText()

    private fun applyUpdate(dmg: File, target: File): Run {
        val method = PlatformUpdateInstaller::class.java.getDeclaredMethod("buildMacUpdateScript", String::class.java, String::class.java)
        method.isAccessible = true
        val script = File(temp(), "apply.sh").also { it.writeText(method.invoke(PlatformUpdateInstaller, dmg.absolutePath, target.absolutePath) as String) }
        val shim = temp()
        File(shim, "open").also { it.writeText("#!/bin/sh\nexit 0\n"); it.setExecutable(true) }
        return sh("PATH='${shim.absolutePath}':\$PATH bash '${script.absolutePath}'")
    }

    private fun installed(marker: String, identity: Identity?): File = makeApp(temp(), marker, identity = identity)

    @Test fun anUpdateSignedByTheInstalledIdentityReplacesTheApp() {
        assumeTrue(DesktopPlatformPaths.isMacOs())
        val id = newIdentity()
        val target = installed("old", id)
        val run = applyUpdate(dmgOf(makeApp(temp(), "new", identity = id)), target)
        assertEquals(run.out, 0, run.code)
        assertEquals("new", marker(target))
    }

    @Test fun anUpdateSignedByAnotherCertificateWithTheSameNameIsRefusedAndTheAppIsUntouched() {
        assumeTrue(DesktopPlatformPaths.isMacOs())
        val mine = newIdentity()
        val other = newIdentity()
        val target = installed("old", mine)
        val run = applyUpdate(dmgOf(makeApp(temp(), "new", identity = other)), target)
        assertTrue(run.out, run.code != 0)
        assertTrue(run.out, run.out.contains("signed by a different identity"))
        assertEquals("old", marker(target))
    }

    @Test fun anAdHocUpdateIsRefusedWhenTheInstalledAppHasAnIdentity() {
        assumeTrue(DesktopPlatformPaths.isMacOs())
        val target = installed("old", newIdentity())
        val run = applyUpdate(dmgOf(makeApp(temp(), "new", identity = null)), target)
        assertTrue(run.out, run.code != 0)
        assertEquals("old", marker(target))
    }

    @Test fun anInstalledAdHocAppMayMoveToTheSignedBuild() {
        assumeTrue(DesktopPlatformPaths.isMacOs())
        val target = installed("old", null)
        val run = applyUpdate(dmgOf(makeApp(temp(), "new", identity = newIdentity())), target)
        assertEquals(run.out, 0, run.code)
        assertEquals("new", marker(target))
    }

    @Test fun aBundleWithAnotherBundleIdIsRefusedEvenWithTheSameCertificate() {
        assumeTrue(DesktopPlatformPaths.isMacOs())
        val id = newIdentity()
        val target = installed("old", id)
        val run = applyUpdate(dmgOf(makeApp(temp(), "new", bundleId = "com.example.other", identity = id)), target)
        assertTrue(run.out, run.code != 0)
        assertTrue(run.out, run.out.contains("signed by a different identity"))
        assertEquals("old", marker(target))
    }
}
