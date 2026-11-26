package com.fileapex.platform

import androidx.compose.ui.graphics.ImageBitmap
import com.fileapex.util.cancellableCatching
import java.io.File
import java.security.MessageDigest

/**
 * Reads cached PDF thumbnails from the freedesktop thumbnail cache (`~/.cache/thumbnails/{x-large,large,normal}/<md5>.png`)
 * with zero subprocesses and zero added libraries. Returns null when no cached thumbnail exists.
 */
internal object LinuxPdfThumbnail {
    private const val MAX_CACHE_PNG_BYTES = 4L * 1024 * 1024
    private val SUBDIRS = listOf("x-large", "large", "normal")

    fun defaultCacheRoot(): File? {
        val xdg = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
        val base = if (xdg != null) File(xdg) else File(System.getProperty("user.home").orEmpty(), ".cache")
        return File(base, "thumbnails").takeIf { it.isDirectory }
    }

    fun render(absolutePath: String, edge: Int, cacheRoot: File? = defaultCacheRoot()): ImageBitmap? {
        if (!absolutePath.startsWith("/") || cacheRoot == null || !cacheRoot.isDirectory) return null
        val fileName = "${md5Hex(canonicalFileUri(absolutePath))}.png"
        for (subdir in SUBDIRS) {
            val cached = File(File(cacheRoot, subdir), fileName)
            if (!cached.isFile || cached.length() !in 1..MAX_CACHE_PNG_BYTES) continue
            val bitmap = cancellableCatching("LinuxPdfThumbnail render") {
                decodeImageBytes(cached.readBytes(), edge)
            }.getOrNull() ?: continue
            return bitmap.centeredOnSquare(edge, PageThumbnailBackdrop)
        }
        return null
    }

    internal fun canonicalFileUri(absolutePath: String): String {
        val normalized = if (absolutePath.startsWith("/")) absolutePath else "/$absolutePath"
        val out = StringBuilder("file://")
        for (byte in normalized.toByteArray(Charsets.UTF_8)) {
            val b = byte.toInt() and 0xFF
            if (isUnreservedUriByte(b) || b == '/'.code) {
                out.append(b.toChar())
            } else {
                out.append('%')
                out.append("0123456789ABCDEF"[b ushr 4])
                out.append("0123456789ABCDEF"[b and 0x0F])
            }
        }
        return out.toString()
    }

    internal fun md5Hex(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun isUnreservedUriByte(b: Int): Boolean =
        (b in 'a'.code..'z'.code) ||
            (b in 'A'.code..'Z'.code) ||
            (b in '0'.code..'9'.code) ||
            b == '-'.code || b == '.'.code || b == '_'.code || b == '~'.code
}
