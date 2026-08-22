package com.fileapex.network

import com.fileapex.platform.UniqueFileNames
import java.io.File
import java.io.RandomAccessFile

object SocketFileStreamer {
    const val BUFFER_BYTES = 256 * 1024
    const val PART_SUFFIX = ".fileapex-part"

    fun partPathFor(finalPath: String): String = "$finalPath$PART_SUFFIX"

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
     * [write] must consume [buffer][0, length) before returning — the array is reused.
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

    fun finalizePart(partPath: String, preferredFinalPath: String): String {
        val destPath = UniqueFileNames.resolve(preferredFinalPath)
        val part = File(partPath)
        val dest = File(destPath)
        dest.parentFile?.mkdirs()
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

    fun deleteQuietly(path: String) {
        runCatching {
            val file = File(path)
            if (file.exists()) file.delete()
        }
    }
}
