package com.fileapex.structure

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bundled artwork ships as lossless WebP, which is smaller than PNG at identical pixels and is decoded on every target
 * (the other drawables already use it). Only small icons may stay PNG. The desktop tray icon is outside the bundle
 * resources the apps decode through Compose, so it is not scanned.
 */
class ImageFormatGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val folders = listOf(
        "shared/src/commonMain/composeResources/drawable",
        "shared/src/androidMain/res/drawable",
        "composeApp/src/androidMain/res/drawable"
    )
    private val maxPngBytes = 10_000L

    @Test
    fun noLargePngIsBundled() {
        val large = folders.flatMap { folder ->
            File(root, folder).listFiles { file -> file.extension == "png" && file.length() > maxPngBytes }.orEmpty().toList()
        }.map { "${it.relativeTo(root).path} (${it.length()} bytes)" }.sorted()
        assertTrue("${large.size} PNG files over $maxPngBytes bytes, convert to lossless WebP:\n" + large.joinToString("\n"), large.isEmpty())
    }
}
