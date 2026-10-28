package com.fileapex.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DesktopJvmFlagsTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    private fun launcherFlags(): List<String> {
        val gradle = File(root, "composeApp/build.gradle.kts").readText()
        val block = gradle.substringAfter("jvmArgs += listOf(").substringBefore(")")
        return Regex("\"(-XX:[^\"]+)\"").findAll(block).map { it.groupValues[1] }.toList()
    }

    private fun finalFlags(vararg flags: String): Map<String, String> {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(listOf(java) + flags + listOf("-XX:+PrintFlagsFinal", "-version")).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        assertEquals("the JVM must accept the launcher flags:\n$text", 0, process.waitFor())
        return text.lines().mapNotNull { Regex("""^\s*\S+\s+(\w+)\s+=\s+(\S+)""").find(it) }.associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun theDesktopLauncherCapsTheHeapAndTrimsTheCollectorThreads() {
        val flags = launcherFlags()
        assertTrue(flags.toString(), flags.containsAll(listOf("-XX:MaxRAM=8g", "-XX:ParallelGCThreads=2", "-XX:ConcGCThreads=1")))
        val values = finalFlags(*flags.toTypedArray())
        assertEquals("2", values["ParallelGCThreads"])
        assertEquals("1", values["ConcGCThreads"])
        val maxHeap = values.getValue("MaxHeapSize").toLong()
        assertTrue("heap ceiling ${maxHeap / 1048576} MB", maxHeap <= 2048L * 1048576)
        assertTrue("initial heap ${values.getValue("InitialHeapSize").toLong() / 1048576} MB", values.getValue("InitialHeapSize").toLong() <= 128L * 1048576)
    }

    @Test
    fun theCollectorStaysG1SoALargeFolderDoesNotStallTheInterface() {
        assertTrue(launcherFlags().none { it.contains("UseSerialGC") || it.contains("UseParallelGC") || it.contains("UseZGC") })
    }
}
