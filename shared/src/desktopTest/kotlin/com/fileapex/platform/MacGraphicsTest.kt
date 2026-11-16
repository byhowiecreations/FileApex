package com.fileapex.platform

import com.fileapex.domain.diagnostics.GpuDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Test

class MacGraphicsTest {
    private val appleSilicon = """
        {"SPDisplaysDataType" : [ {
          "_name" : "Apple M6",
          "spdisplays_ndrvs" : [ { "_name" : "Color LCD", "_spdisplays_resolution" : "1800 x 1169 @ 120.00Hz" } ],
          "spdisplays_vendor" : "sppci_vendor_Apple",
          "sppci_bus" : "spdisplays_builtin",
          "sppci_cores" : "12",
          "sppci_device_type" : "spdisplays_gpu",
          "sppci_model" : "Apple M6"
        } ]}
    """.trimIndent()

    private val intelMacWithDiscrete = """
        {"SPDisplaysDataType" : [
          { "_name" : "Intel UHD Graphics 630", "spdisplays_vendor" : "sppci_vendor_Intel", "spdisplays_vram_shared" : "1536 MB",
            "sppci_device_type" : "spdisplays_gpu", "sppci_model" : "Intel UHD Graphics 630" },
          { "_name" : "AMD Radeon Pro 5500M", "spdisplays_vendor" : "sppci_vendor_amd", "spdisplays_vram" : "8 GB",
            "sppci_device_type" : "spdisplays_gpu", "sppci_model" : "AMD Radeon Pro 5500M" }
        ]}
    """.trimIndent()

    @Test
    fun appleSiliconIsNamedWithItsGpuCoresAndHasNoDedicatedMemory() {
        assertEquals(listOf(GpuDiagnostics("Apple M6 (12-core GPU)", null)), MacGraphics.parse(appleSilicon))
    }

    @Test
    fun anIntelMacListsBothCardsAndOnlyTheDiscreteOneHasDedicatedMemory() {
        assertEquals(
            listOf(
                GpuDiagnostics("Intel UHD Graphics 630", null),
                GpuDiagnostics("AMD Radeon Pro 5500M", 8L * 1024 * 1024 * 1024)
            ),
            MacGraphics.parse(intelMacWithDiscrete)
        )
    }

    @Test
    fun memoryInMegabytesIsUnderstood() {
        val json = """{"SPDisplaysDataType":[{"sppci_model":"AMD Radeon R9 M370X","spdisplays_vram":"2048 MB"}]}"""
        assertEquals(2048L * 1024 * 1024, MacGraphics.parse(json).single().memoryBytes)
    }

    @Test
    fun garbageOrAMissingSectionGivesNoCards() {
        assertEquals(emptyList<GpuDiagnostics>(), MacGraphics.parse(""))
        assertEquals(emptyList<GpuDiagnostics>(), MacGraphics.parse("not json"))
        assertEquals(emptyList<GpuDiagnostics>(), MacGraphics.parse("""{"SPHardwareDataType":[]}"""))
    }
}
