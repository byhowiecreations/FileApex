package com.fileapex.platform

import com.fileapex.domain.diagnostics.GpuDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Test

class LinuxGraphicsTest {
    private val lspci = """
        00:02.0 "VGA compatible controller" "Intel Corporation" "Alder Lake-P GT2 [Iris Xe Graphics]" -r0c -p00 "Lenovo" "Device 22d2"
        00:1f.3 "Audio device" "Intel Corporation" "Alder Lake PCH-P High Definition Audio Controller" -r01 -p80 "Lenovo" "Device 22d2"
        01:00.0 "3D controller" "NVIDIA Corporation" "GA107M [GeForce RTX 3050 Mobile]" -ra1 -p00 "Lenovo" "Device 22d2"
        03:00.0 "VGA compatible controller" "Advanced Micro Devices, Inc. [AMD/ATI]" "Navi 23 [Radeon RX 6600/6600 XT/6600M]" -rc1 -p00 "Sapphire" "Device e445"
    """.trimIndent()

    private val nvidiaSmi = "00000000:01:00.0, 4096"

    @Test
    fun onlyDisplayClassDevicesAreListedWithTheirVendorNames() {
        val names = LinuxGraphics.gpus(lspci, nvidiaSmi = null) { null }.map { it.name }
        assertEquals(
            listOf(
                "Intel Alder Lake-P GT2 [Iris Xe Graphics]",
                "NVIDIA GA107M [GeForce RTX 3050 Mobile]",
                "AMD Navi 23 [Radeon RX 6600/6600 XT/6600M]"
            ),
            names
        )
    }

    @Test
    fun nvidiaMemoryComesFromNvidiaSmiByPciAddress() {
        val gpus = LinuxGraphics.gpus(lspci, nvidiaSmi) { null }
        assertEquals(4096L * 1024 * 1024, gpus.single { it.name.startsWith("NVIDIA") }.memoryBytes)
        assertEquals(null, gpus.single { it.name.startsWith("Intel") }.memoryBytes)
    }

    @Test
    fun amdMemoryComesFromTheSysfsValueForItsPciAddress() {
        val asked = mutableListOf<String>()
        val gpus = LinuxGraphics.gpus(lspci, nvidiaSmi = null) { slot -> asked += slot; if (slot == "03:00.0") 8_573_157_376L else null }
        assertEquals(8_573_157_376L, gpus.single { it.name.startsWith("AMD") }.memoryBytes)
        assertEquals(setOf("00:02.0", "01:00.0", "03:00.0"), asked.toSet())
    }

    @Test
    fun noLspciMeansNoCardsInsteadOfAGuess() {
        assertEquals(emptyList<GpuDiagnostics>(), LinuxGraphics.gpus(null, null) { null })
        assertEquals(emptyList<GpuDiagnostics>(), LinuxGraphics.gpus("", "") { null })
    }

    @Test
    fun theRealUbuntuVirtualMachineAdapterIsListedWithItsVendor() {
        val vm = """00:02.0 "VGA compatible controller" "VMware" "SVGA II Adapter" -p00 "VMware" "SVGA II Adapter""""
        assertEquals(listOf(GpuDiagnostics("VMware SVGA II Adapter", null)), LinuxGraphics.gpus(vm, null) { null })
    }
}
