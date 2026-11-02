package com.fileapex.platform

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The launcher file of every Linux package names the window class the app sets, or the dock cannot match the window and shows a gear. */
class LinuxLauncherTemplateTest {
    private val template = File("../composeApp/linux/FileApex.desktop")
    private val build = File("../composeApp/build.gradle.kts").readText()

    @Test fun theTemplateExistsAndNamesTheWindowClassTheAppSets() {
        assertTrue("the launcher template is missing", template.isFile)
        val lines = template.readLines()
        assertEquals(listOf("FileApex"), lines.filter { it.startsWith("StartupWMClass=") }.map { it.removePrefix("StartupWMClass=") })
        assertEquals(DesktopJvmStartup.LINUX_WINDOW_CLASS, "FileApex")
    }

    @Test fun theTemplateKeepsTheFieldsJpackageFillsIn() {
        val text = template.readText()
        listOf("APPLICATION_NAME", "APPLICATION_DESCRIPTION", "APPLICATION_LAUNCHER", "APPLICATION_ICON", "DEPLOY_BUNDLE_CATEGORY", "DESKTOP_MIMES").forEach {
            assertTrue("$it is missing from the template", text.contains(it))
        }
    }

    @Test fun theDebTheRpmAndTheAppImageAllUseThatOneTemplate() {
        assertTrue(build.contains("linux/FileApex.desktop"))
        assertTrue("the AppImage must not carry its own copy of the launcher", !build.contains("StartupWMClass=FileApex"))
        assertTrue("jpackage must package the app image with our resource directory", build.contains("\"--resource-dir\"") && build.contains("\"--app-image\""))
        listOf("packageLinuxDeb", "packageLinuxRpm").forEach { assertTrue("$it is missing", build.contains("\"$it\"")) }
    }

    @Test fun theShippedPackagesAreTheRepackagedOnes() {
        val ship = build.substringAfter("tasks.register(\"shipLinuxPackages\")").substringBefore("onlyIf")
        assertTrue(ship.contains("\"packageLinuxDeb\"") && ship.contains("\"packageLinuxRpm\""))
        assertTrue("shipping must not take Compose's own packages", !ship.contains("\"packageReleaseDeb\""))
    }

    @Test fun theLinkSchemeIsTheOnlyMimeLineAndJpackageHasNoAssociationsToAddAnother() {
        val lines = template.readLines()
        assertTrue("jpackage still gets its own MIME placeholder", lines.contains("DESKTOP_MIMES"))
        assertEquals("a second MimeType key would make the launcher invalid", listOf("MimeType=x-scheme-handler/fileapex;"), lines.filter { it.startsWith("MimeType=") })
        assertTrue("with file associations jpackage would write a second MimeType line", !build.contains("fileAssociation") && !build.contains("--file-associations"))
        assertTrue(lines.contains("Categories=DEPLOY_BUNDLE_CATEGORY"))
    }

    @Test fun theLauncherIconIsTheAppIconNotJpackagesDefault() {
        val task = build.substringAfter("fun registerLinuxPackage").substringBefore("registerLinuxPackage(\"packageLinuxDeb\"")
        assertTrue("jpackage must be given the app icon as FileApex.png in the resource directory", task.contains("icons/FileApex-1024.png") && task.contains("resolve(\"FileApex.png\")"))
        assertTrue(File("../composeApp/icons/FileApex-1024.png").isFile)
    }
}
