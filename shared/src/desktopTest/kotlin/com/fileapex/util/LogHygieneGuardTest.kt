package com.fileapex.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** A device name is personal data: log lines use the device id instead. */
class LogHygieneGuardTest {
    private val roots = listOf("src", "../composeApp/src").map(::File)
    private val logLine = Regex("""(println\(|Log\.[dievw]\()""")
    private val allowedFiles = setOf("DesktopNetworkingSmokeProbe.kt")

    @Test
    fun noLogLineNamesADevice() {
        val offenders = roots.flatMap { it.walkTopDown().filter { f -> f.extension == "kt" && "/build/" !in f.path } }
            .filter { it.name !in allowedFiles && "Test" !in it.name && "/cli/" !in it.path }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    if (logLine.containsMatchIn(line) && "deviceName" in line) "${file.name}:${i + 1}" else null
                }
            }
        assertEquals("log lines that print a device name: $offenders", emptyList<String>(), offenders)
    }
}
