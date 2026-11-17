package com.fileapex.platform

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Caps for extracting a zip that may come from a peer. Sizes are counted on the bytes actually written, because
 * the sizes in a zip header are attacker controlled. The ratio is checked per entry once it has produced
 * [ratioCheckFromBytes], so small repetitive text files are not mistaken for bombs.
 */
internal data class ZipExtractLimits(
    val maxEntries: Int = 20_000,
    val maxTotalBytes: Long = 4L shl 30,
    val maxEntryBytes: Long = 4L shl 30,
    val maxRatio: Int = 200,
    val ratioCheckFromBytes: Long = 1L shl 20
) {
    companion object {
        /** Free space that extraction never uses up, so a large archive cannot fill the device. */
        const val FREE_SPACE_RESERVE_BYTES: Long = 64L shl 20
    }
}

/**
 * Extracts [zipFile] into a new folder next to it and returns that folder. An existing folder of the same name is
 * never touched: the new one gets a " (2)", " (3)" suffix. If the archive is refused or extraction fails, the
 * folder this call created is removed.
 */
internal fun extractZipSafely(zipFile: File, limits: ZipExtractLimits, availableBytes: Long): String {
    val parent = zipFile.absoluteFile.parentFile ?: throw IOException("archive has no parent folder")
    val destDir = createUniqueFolder(parent, zipFile.nameWithoutExtension)
    var completed = false
    try {
        val freeSpaceCap = (availableBytes - ZipExtractLimits.FREE_SPACE_RESERVE_BYTES).coerceAtLeast(0L)
        extractEntries(zipFile, destDir, limits, minOf(limits.maxTotalBytes, freeSpaceCap))
        completed = true
    } finally {
        if (!completed) destDir.deleteRecursively()
    }
    return destDir.absolutePath
}

private fun createUniqueFolder(parent: File, baseName: String): File {
    var index = 1
    while (true) {
        val candidate = File(parent, if (index == 1) baseName else "$baseName ($index)")
        if (candidate.mkdir()) return candidate
        if (!candidate.exists()) throw IOException("cannot create extraction folder")
        index++
    }
}

private fun extractEntries(zipFile: File, destDir: File, limits: ZipExtractLimits, totalCap: Long) {
    val canonicalDest = destDir.canonicalPath
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var entryCount = 0
    var totalBytes = 0L
    CountingInputStream(FileInputStream(zipFile)).use { counted ->
        ZipInputStream(counted).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (++entryCount > limits.maxEntries) throw ArchiveRefusedException("too many entries")
                val out = File(destDir, entry.name)
                val canonicalOut = out.canonicalPath
                val inside = canonicalOut.startsWith(canonicalDest + File.separator)
                if (!inside && !(entry.isDirectory && canonicalOut == canonicalDest)) {
                    throw ArchiveRefusedException("entry escapes destination")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    val compressedStart = counted.count
                    var entryBytes = 0L
                    FileOutputStream(out).use { output ->
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            entryBytes += read
                            totalBytes += read
                            if (entryBytes > limits.maxEntryBytes) throw ArchiveRefusedException("entry too large")
                            if (totalBytes > totalCap) throw ArchiveRefusedException("archive too large for the free space or the limit")
                            if (entryBytes > limits.ratioCheckFromBytes) {
                                val compressed = (counted.count - compressedStart).coerceAtLeast(1L)
                                if (entryBytes / compressed > limits.maxRatio) {
                                    throw ArchiveRefusedException("compression ratio too high")
                                }
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
}

private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) count += it }

    override fun skip(n: Long): Long = super.skip(n).also { count += it }
}

private const val COPY_BUFFER_BYTES = 64 * 1024
