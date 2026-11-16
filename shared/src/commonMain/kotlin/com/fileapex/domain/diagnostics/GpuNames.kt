package com.fileapex.domain.diagnostics

object GpuNames {
    private val softwareRenderers = listOf("swiftshader", "llvmpipe", "(software)")

    /** The GL renderer string names the GPU on Android; a software renderer says nothing about the device. */
    fun fromGlRenderer(renderer: String?): String? {
        val name = renderer?.trim()?.replace(Regex("""\s+"""), " ").orEmpty()
        if (name.isEmpty()) return null
        if (softwareRenderers.any { name.contains(it, ignoreCase = true) }) return null
        return name
    }
}
