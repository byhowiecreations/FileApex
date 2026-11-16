package com.fileapex.platform

import com.fileapex.domain.diagnostics.GpuDiagnostics
import java.io.File

/** Graphics cards from `lspci -mm`, with dedicated memory from `nvidia-smi` or the amdgpu sysfs file. */
internal object LinuxGraphics {
    private val displayClasses = setOf("VGA compatible controller", "3D controller", "Display controller")
    private val lspciLine = Regex("""^(\S+)\s+"([^"]*)"\s+"([^"]*)"\s+"([^"]*)"""")
    private val nvidiaLine = Regex("""^\s*([0-9A-Fa-f:.]+)\s*,\s*(\d+)\s*$""")

    fun read(): List<GpuDiagnostics> = gpus(
        lspci = readCommandOutput("lspci", "-mm"),
        nvidiaSmi = readCommandOutput("nvidia-smi", "--query-gpu=pci.bus_id,memory.total", "--format=csv,noheader,nounits")
    ) { slot ->
        val file = File("/sys/bus/pci/devices/0000:$slot/mem_info_vram_total")
        // Only amdgpu exposes this file, so a missing file is normal.
        if (!file.isFile) null else file.readText().trim().toLongOrNull()?.takeIf { it > 0 }
    }

    fun gpus(lspci: String?, nvidiaSmi: String?, amdVramBytes: (slot: String) -> Long?): List<GpuDiagnostics> {
        if (lspci.isNullOrBlank()) return emptyList()
        val nvidiaMemory = nvidiaSmi.orEmpty().lines().mapNotNull { line ->
            val match = nvidiaLine.find(line) ?: return@mapNotNull null
            match.groupValues[1].lowercase().takeLast(SLOT_LENGTH) to match.groupValues[2].toLong() * 1024L * 1024L
        }.toMap()
        return lspci.lines().mapNotNull { line ->
            val match = lspciLine.find(line) ?: return@mapNotNull null
            val (slot, kind, vendor, device) = match.destructured
            if (kind !in displayClasses) return@mapNotNull null
            GpuDiagnostics("${vendorName(vendor)} $device", nvidiaMemory[slot.lowercase()] ?: amdVramBytes(slot))
        }
    }

    private fun vendorName(vendor: String): String = when {
        vendor.contains("NVIDIA", ignoreCase = true) -> "NVIDIA"
        vendor.contains("Advanced Micro Devices", ignoreCase = true) || vendor.contains("AMD", ignoreCase = true) -> "AMD"
        vendor.contains("Intel", ignoreCase = true) -> "Intel"
        else -> vendor.replace(Regex("""\s*(Corporation|Corp\.?|Inc\.?|Ltd\.?|,)\s*$""", RegexOption.IGNORE_CASE), "").trim()
    }

    private const val SLOT_LENGTH = 7
}
