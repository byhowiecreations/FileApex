package com.fileapex.platform

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Silicon and Intel builds must bundle the same Java, and the Go toolchain must match go.mod. The JDK version is written
 * once, in toolchain.properties; everything that needs it reads that file, so no other file may spell a JDK version.
 */
class JdkVersionGuardTest {
    private val readers = listOf("gradlew", "gradlew.bat", "composeApp/build.gradle.kts")
    private val literal = Regex("""jdk-21\.0\.\d+\+\d+""")
    private fun text(name: String) = File("../$name").readText()

    private val jdk = Regex("""(?m)^jdk=(\S+)$""").find(text("toolchain.properties"))?.groupValues?.get(1)

    @Test fun theJdkVersionIsWrittenOnceInToolchainProperties() {
        assertTrue("toolchain.properties needs jdk=21.<minor>.<patch>+<build>", jdk != null && Regex("""21\.0\.\d+\+\d+""").matches(jdk))
        (readers + "gradle.properties" + "scripts/build-fileapex.sh").forEach {
            assertFalse("$it spells a JDK version; read toolchain.properties instead", literal.containsMatchIn(text(it)))
        }
    }

    @Test fun everyReaderOfTheJdkVersionReadsToolchainProperties() {
        (readers + "scripts/build-fileapex.sh").forEach {
            assertTrue("$it must read toolchain.properties", text(it).contains("toolchain.properties"))
        }
    }

    @Test fun theBuildJdkIsTheCurrentTemurinPatch() {
        assertEquals("21.0.13+9", jdk)
    }

    @Test fun theGoToolchainDefaultsAgreeWithGoMod() {
        val goMod = Regex("""(?m)^go (\S+)$""").find(text("native/tsnet/go.mod"))!!.groupValues[1]
        listOf("macos/scripts/build_tsnet.sh", "windows/scripts/build_tsnet.bat").forEach {
            val pins = Regex("""go\d+\.\d+\.\d+""").findAll(text(it).lines().filter { l -> l.contains("GOTOOLCHAIN=") }.joinToString("\n")).map { m -> m.value }.toSet()
            assertEquals("$it pins one Go toolchain", setOf("go$goMod"), pins)
        }
    }
}
