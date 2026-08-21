package com.fileapex.util

import java.io.File
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
