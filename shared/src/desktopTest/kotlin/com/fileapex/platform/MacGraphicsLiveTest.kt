package com.fileapex.platform

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class MacGraphicsLiveTest {
    @Test
    fun thisMacReportsItsOwnGraphics() {
        assumeTrue(System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true))
        val output = readCommandOutput("system_profiler", "SPDisplaysDataType", "-json")
        assumeTrue("system_profiler gave no output here", !output.isNullOrBlank())
        val gpus = MacGraphics.parse(output.orEmpty())
        assertTrue("no graphics card parsed from this Mac: $output", gpus.isNotEmpty() && gpus.all { it.name.isNotBlank() })
    }
}
