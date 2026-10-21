package com.fileapex.network

import com.fileapex.platform.UniqueFileNames
import java.io.File
import java.io.RandomAccessFile

object SocketFileStreamer {
    const val BUFFER_BYTES = 256 * 1024
    const val PART_SUFFIX = ".fileapex-part"

    /** Free space left on the receiving volume after an upload, so one transfer cannot fill the disk. */
    const val DISK_RESERVE_BYTES = 256L * 1024 * 1024

    /** False when [incomingBytes] plus the reserve would not fit where [path] is going to be written. */
    fun hasRoomFor(path: String, incomingBytes: Long): Boolean {
        var dir: File? = File(path).absoluteFile.parentFile
        while (dir != null && !dir.exists()) dir = dir.parentFile
        val free = dir?.usableSpace?.takeIf { it > 0L } ?: return true
        return incomingBytes.coerceAtLeast(0L) + DISK_RESERVE_BYTES <= free
    }

    // Kept apart from PART_SUFFIX: legacy resume treats part length as the offset, which is
    // wrong for a file written out of order.
    const val SEGMENT_PART_SUFFIX = ".fileapex-segpart"

    fun partPathFor(finalPath: String, transactionId: String = ""): String {
        val key = transferKey(transactionId)
        return if (key.isEmpty()) "$finalPath$PART_SUFFIX" else "$finalPath.$key$PART_SUFFIX"
    }

    fun segmentPartPathFor(finalPath: String, transactionId: String = ""): String {
        val key = transferKey(transactionId)
        return if (key.isEmpty()) "$finalPath$SEGMENT_PART_SUFFIX" else "$finalPath.$key$SEGMENT_PART_SUFFIX"
    }

    // The id comes from a peer, so it is cut down to a plain file-name piece beside the target.
    private fun transferKey(transactionId: String): String =
        transactionId.trim().take(64).map { ch ->
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '_'
        }.joinToString("")

    fun isTransferScratchFile(name: String): Boolean =
        name.endsWith(PART_SUFFIX) ||
            name.endsWith(SEGMENT_PART_SUFFIX) ||
            name.contains("$SEGMENT_PART_SUFFIX.")

    fun fileLength(path: String): Long {
        val file = File(path)
        return if (file.isFile) file.length().coerceAtLeast(0L) else 0L
    }

    fun openAppender(path: String, offset: Long): RandomAccessFile {
        require(offset >= 0L) { "Invalid resume offset $offset" }
        val file = File(path)
        file.parentFile?.mkdirs()
        val raf = RandomAccessFile(file, "rw")
        try {
            val existing = raf.length()
            when {
                offset == 0L -> raf.setLength(0L)
                offset > existing -> {
                    raf.close()
                    error("Resume offset $offset is past existing $existing bytes")
                }
                offset < existing -> raf.setLength(offset)
            }
            raf.seek(offset)
            return raf
        } catch (error: Throwable) {
            runCatching { raf.close() }
            throw error
        }
    }

    /**
     * [write] must consume [buffer][0, length) before returning, the array is reused.
     */
    fun streamFromOffset(
        sourcePath: String,
        offset: Long,
        byteLimit: Long = Long.MAX_VALUE,
        write: (buffer: ByteArray, length: Int) -> Unit
    ): Long {
        RandomAccessFile(sourcePath, "r").use { raf ->
            val size = raf.length()
            require(offset >= 0L && offset <= size) {
                "Invalid stream offset $offset for size $size"
            }
            raf.seek(offset)
            val buffer = ByteArray(BUFFER_BYTES)
            var sent = 0L
            val limit = byteLimit.coerceAtLeast(0L)
            while (sent < limit) {
                val want = minOf(buffer.size.toLong(), limit - sent).toInt()
                val read = raf.read(buffer, 0, want)
                if (read <= 0) break
                write(buffer, read)
                sent += read.toLong()
            }
            return sent
        }
    }

    private val finalizeLock = Any()

    fun finalizePart(partPath: String, preferredFinalPath: String, overwrite: Boolean = false): String {
        // Picking the free name and moving onto it must be one step: two uploads of the same
        // name finishing together would otherwise both pick the same free name.
        synchronized(finalizeLock) {
            val destPath = if (overwrite) preferredFinalPath else UniqueFileNames.resolve(preferredFinalPath)
            val part = File(partPath)
            val dest = File(destPath)
            dest.parentFile?.mkdirs()
            if (overwrite && dest.exists()) dest.delete()
            if (part.renameTo(dest)) {
                return destPath
            }
            RandomAccessFile(part, "r").use { input ->
                RandomAccessFile(dest, "rw").use { output ->
                    output.setLength(0L)
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            part.delete()
            return destPath
        }
    }

    fun deleteQuietly(path: String) {
        runCatching {
            val file = File(path)
            if (file.exists()) file.delete()
        }
    }
}
