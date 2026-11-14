package com.fileapex.platform

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps version.md, browser-extension/, and composeApp/build.gradle.kts's
 * firefoxExtensionFiles list in sync for the 1-tap Bulletin Board extension.
 */
class FirefoxExtensionPackagingTest {
    private val repoRoot = File("..").canonicalFile

    @Test
    fun versionMdMatchesExtensionManifestVersion() {
        val versionLine = File(repoRoot, "version.md").readLines()
            .firstOrNull { it.startsWith("extension_version=") }
            ?: error("version.md missing extension_version=")
        val versionMd = versionLine.substringAfter("extension_version=").trim()

        val manifest = Json.parseToJsonElement(
            File(repoRoot, "browser-extension/manifest.json").readText()
        ).jsonObject
        val manifestVersion = manifest["version"]?.jsonPrimitive?.content.orEmpty()

        assertEquals("version.md extension_version must match manifest.json", manifestVersion, versionMd)
    }

    @Test
    fun extensionIsOneTapBulletinBoardWithoutPopupOrPortSettings() {
        val extDir = File(repoRoot, "browser-extension")
        assertFalse("popup.html should not exist", File(extDir, "popup.html").exists())
        assertFalse("popup.js should not exist", File(extDir, "popup.js").exists())

        val manifest = Json.parseToJsonElement(File(extDir, "manifest.json").readText()).jsonObject
        assertNull("action.default_popup must be absent for 1-tap action", manifest["action"]?.jsonObject?.get("default_popup"))
        val perms = manifest["permissions"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        assertEquals(listOf("activeTab", "notifications"), perms)

        val bgText = File(extDir, "background.js").readText()
        assertTrue("background.js must post to bulletin", bgText.contains("/api/v1/web/post-bulletin"))
        assertFalse("background.js must not configure port", bgText.contains("serverPort"))
        assertFalse("background.js must not call send-clipboard", bgText.contains("send-clipboard"))
    }

    @Test
    fun buildGradlePackagesAllManifestAndRuntimeFiles() {
        val manifest = Json.parseToJsonElement(
            File(repoRoot, "browser-extension/manifest.json").readText()
        ).jsonObject

        val required = linkedSetOf("manifest.json")
        manifest["background"]?.jsonObject?.get("scripts")?.jsonArray?.forEach {
            required += it.jsonPrimitive.content
        }
        manifest["action"]?.jsonObject?.get("default_popup")?.jsonPrimitive?.content?.let {
            required += it
        }
        manifest["icons"]?.jsonObject?.values?.forEach {
            required += it.jsonPrimitive.content
        }
        manifest["action"]?.jsonObject?.get("default_icon")?.jsonObject?.values?.forEach {
            required += it.jsonPrimitive.content
        }

        val extDir = File(repoRoot, "browser-extension")
        extDir.walkTopDown()
            .filter { it.isFile && it.name != "package.json" && !it.name.startsWith(".") }
            .forEach { required += it.relativeTo(extDir).invariantSeparatorsPath }

        val gradleText = File(repoRoot, "composeApp/build.gradle.kts").readText()
        val block = Regex("""private val firefoxExtensionFiles = listOf\((.*?)\)""", RegexOption.DOT_MATCHES_ALL)
            .find(gradleText)?.groupValues?.get(1)
            ?: error("Missing firefoxExtensionFiles in composeApp/build.gradle.kts")
        val packaged = Regex(""""([^"]+)"""").findAll(block).map { it.groupValues[1] }.toSet()

        val missing = required - packaged
        assertTrue("Missing from firefoxExtensionFiles: $missing", missing.isEmpty())
        assertEquals("firefoxExtensionFiles must match required files", required, packaged)
    }
}
