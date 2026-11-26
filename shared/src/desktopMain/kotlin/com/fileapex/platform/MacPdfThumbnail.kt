package com.fileapex.platform

import androidx.compose.ui.graphics.ImageBitmap
import com.fileapex.util.cancellableCatching
import java.nio.file.Files
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * The first page of a PDF on macOS, drawn by the system's own `sips` tool, so no PDF library is bundled. At most two run
 * at once, each is stopped after five seconds, and only an absolute path is passed (never through a shell).
 */
internal object MacPdfThumbnail {
    private const val SIPS = "/usr/bin/sips"
    private const val TIMEOUT_SECONDS = 5L
    private const val MAX_OUTPUT_BYTES = 4L * 1024 * 1024
    private val gate = Semaphore(2)

    fun render(absolutePath: String, edge: Int): ImageBitmap? {
        if (!absolutePath.startsWith("/") || !java.io.File(SIPS).canExecute()) return null
        gate.acquire()
        try {
            val output = Files.createTempFile("fileapex-pdf-", ".png")
            try {
                return cancellableCatching("MacPdfThumbnail render") {
                    val process = ProcessBuilder(SIPS, "-s", "format", "png", "-Z", edge.toString(), absolutePath, "--out", output.toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                    if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        return@cancellableCatching null
                    }
                    if (process.exitValue() != 0 || Files.size(output) !in 1..MAX_OUTPUT_BYTES) return@cancellableCatching null
                    decodeImageBytes(Files.readAllBytes(output), edge)?.centeredOnSquare(edge, PageThumbnailBackdrop)
                }.getOrNull()
            } finally {
                Files.deleteIfExists(output)
            }
        } finally {
            gate.release()
        }
    }
}
