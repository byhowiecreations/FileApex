package com.fileapex.domain.clipboard

import com.fileapex.platform.defaultDownloadsDir
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class ReceivedClipboardImage(
    val bytes: ByteArray,
    val mimeType: String,
    val sourceDeviceName: String,
    val receivedAtMs: Long,
    val savedPath: String? = null
)

/** Holds the most recent received clipboard image so UI actions can save it to Downloads/FileApex. */
object ClipboardImageInbox {
    private val _latest = MutableStateFlow<ReceivedClipboardImage?>(null)
    val latest: StateFlow<ReceivedClipboardImage?> = _latest.asStateFlow()

    fun record(
        bytes: ByteArray,
        mimeType: String,
        sourceDeviceName: String,
        nowMs: Long = TimeUtils.now()
    ) {
        if (bytes.isEmpty()) return
        _latest.value = ReceivedClipboardImage(
            bytes = bytes,
            mimeType = mimeType,
            sourceDeviceName = sourceDeviceName,
            receivedAtMs = nowMs
        )
    }

    fun saveLatest(destinationDir: String = defaultDownloadsDir()): String? {
        val current = _latest.value ?: return null
        val saved = ClipboardImageStorage.saveToDirectory(
            bytes = current.bytes,
            mimeType = current.mimeType,
            sourceDeviceName = current.sourceDeviceName,
            destinationDir = destinationDir,
            nowEpochMs = current.receivedAtMs
        ) ?: return null
        _latest.value = current.copy(savedPath = saved)
        return saved
    }

    fun dismiss() {
        _latest.value = null
    }
}

/** Atomic, path-safe writer for saving received clipboard images into Downloads/FileApex. */
object ClipboardImageStorage {
    fun saveToDirectory(
        bytes: ByteArray,
        mimeType: String,
        sourceDeviceName: String,
        destinationDir: String,
        nowEpochMs: Long = TimeUtils.now()
    ): String? {
        if (bytes.isEmpty() || destinationDir.isBlank()) return null
        val dir = File(destinationDir)
        if (!dir.exists() && !dir.mkdirs()) return null
        if (!dir.isDirectory) return null

        val safeSender = sanitizeSenderLabel(sourceDeviceName)
        val ext = extensionForMime(mimeType)
        val stem = "Clipboard_${safeSender}_${nowEpochMs}"
        val target = resolveUniqueTarget(dir, stem, ext) ?: return null
        val tmp = File(dir, ".${target.name}.${nowEpochMs}.tmp")
        return try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = false)
                tmp.delete()
            }
            target.absolutePath
        } catch (error: java.io.IOException) {
            tmp.delete()
            println("ClipboardImageStorage: could not save a received image (${error::class.simpleName})")
            null
        } catch (error: SecurityException) {
            tmp.delete()
            println("ClipboardImageStorage: could not save a received image (${error::class.simpleName})")
            null
        }
    }

    private fun sanitizeSenderLabel(raw: String): String {
        val cleaned = raw
            .replace('/', '_')
            .replace('\\', '_')
            .replace("..", "_")
            .replace(Regex("""[<>:"|?*\p{Cntrl}]"""), "_")
            .trim()
            .take(40)
        return cleaned.ifEmpty { "device" }
    }

    /**
     * The copy of a received image that is handed to the system clipboard. Other apps read it through a content URI, so
     * it cannot be sealed. One copy is kept: written atomically, replacing the last, whatever its type.
     */
    fun writeClipboardCopy(cacheDir: String, mimeType: String, bytes: ByteArray): File? {
        if (bytes.isEmpty()) return null
        val dir = File(cacheDir)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val target = File(dir, "$CLIPBOARD_COPY_STEM.${extensionForMime(mimeType)}")
        val tmp = File(dir, ".${target.name}.tmp")
        return try {
            tmp.writeBytes(bytes)
            java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            dir.listFiles { file -> file.name.startsWith("$CLIPBOARD_COPY_STEM.") && file != target }?.forEach { it.delete() }
            target
        } catch (error: java.io.IOException) {
            tmp.delete()
            println("ClipboardImageStorage: could not write the clipboard copy (${error::class.simpleName})")
            null
        } catch (error: SecurityException) {
            tmp.delete()
            println("ClipboardImageStorage: could not write the clipboard copy (${error::class.simpleName})")
            null
        }
    }

    private const val CLIPBOARD_COPY_STEM = "clipboard-remote"

    private fun extensionForMime(mimeType: String): String {
        val lower = mimeType.lowercase()
        return when {
            lower.contains("png") -> "png"
            lower.contains("webp") -> "webp"
            lower.contains("gif") -> "gif"
            else -> "jpg"
        }
    }

    private fun resolveUniqueTarget(dir: File, stem: String, ext: String): File? {
        val canonicalDir = dir.canonicalFile
        val initial = File(canonicalDir, "$stem.$ext")
        if (initial.parentFile?.canonicalFile != canonicalDir) return null
        if (!initial.exists()) return initial
        for (i in 1..999) {
            val candidate = File(canonicalDir, "$stem ($i).$ext")
            if (!candidate.exists()) return candidate
        }
        return null
    }
}
