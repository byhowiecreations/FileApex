package com.fileapex.util

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest

actual fun sha256Hex(data: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(data)
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}

actual fun sha256HexFile(absolutePath: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    File(absolutePath).inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

actual suspend fun sha256HexFileStreaming(absolutePath: String, onProgress: (bytesRead: Long) -> Unit): String =
    withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        File(absolutePath).inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                total += read
                onProgress(total)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
