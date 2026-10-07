package com.fileapex.cloud.drive

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chunked AES-256-GCM for Drive relay files. Each chunk is sealed on its own with a counter nonce,
 * and the final chunk is flagged, so a tampered, reordered or truncated file fails to decrypt.
 * Memory use is one chunk regardless of file size.
 */
object DriveRelayCrypto {
    const val SCHEME = "FXE1"

    private const val CHUNK_BYTES = 1 shl 20
    private const val MAX_CHUNK_BYTES = 16 shl 20
    private const val TAG_BYTES = 16
    private const val PREFIX_BYTES = 7
    private const val HEADER_BYTES = 4 + 4 + PREFIX_BYTES
    private val MAGIC = SCHEME.toByteArray(Charsets.US_ASCII)

    class Result(val plainSizeBytes: Long, val plainSha256: String)

    fun newKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    fun encryptFile(source: File, dest: File, key: ByteArray, checkCancelled: () -> Unit = {}): Result {
        val prefix = ByteArray(PREFIX_BYTES).also { SecureRandom().nextBytes(it) }
        val header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).putInt(CHUNK_BYTES).put(prefix).array()
        val digest = MessageDigest.getInstance("SHA-256")
        var plainSize = 0L
        source.inputStream().buffered().use { input ->
            dest.outputStream().buffered().use { output ->
                output.write(header)
                var current = ByteArray(CHUNK_BYTES)
                var currentLen = fill(input, current)
                var next = ByteArray(CHUNK_BYTES)
                var counter = 0
                while (true) {
                    checkCancelled()
                    val nextLen = if (currentLen == CHUNK_BYTES) fill(input, next) else 0
                    val last = nextLen == 0
                    digest.update(current, 0, currentLen)
                    plainSize += currentLen
                    output.write(seal(Cipher.ENCRYPT_MODE, key, prefix, counter, last, header, current, currentLen))
                    if (last) break
                    counter += 1
                    val swap = current
                    current = next
                    next = swap
                    currentLen = nextLen
                }
            }
        }
        return Result(plainSize, digest.digest().toHex())
    }

    fun decryptFile(source: File, dest: File, key: ByteArray, checkCancelled: () -> Unit = {}): Result {
        val digest = MessageDigest.getInstance("SHA-256")
        var plainSize = 0L
        source.inputStream().buffered().use { input ->
            val header = ByteArray(HEADER_BYTES)
            if (fill(input, header) != HEADER_BYTES) throw EOFException("encrypted file header is incomplete")
            val buffer = ByteBuffer.wrap(header)
            val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
            if (!magic.contentEquals(MAGIC)) throw IOException("not a FileApex encrypted file")
            val chunk = buffer.int
            if (chunk !in 1..MAX_CHUNK_BYTES) throw IOException("unsupported chunk size")
            val prefix = ByteArray(PREFIX_BYTES).also { buffer.get(it) }
            dest.outputStream().buffered().use { output ->
                var current = ByteArray(chunk + TAG_BYTES)
                var currentLen = fill(input, current)
                if (currentLen < TAG_BYTES) throw EOFException("encrypted file is truncated")
                var next = ByteArray(chunk + TAG_BYTES)
                var counter = 0
                while (true) {
                    checkCancelled()
                    val nextLen = if (currentLen == chunk + TAG_BYTES) fill(input, next) else 0
                    val last = nextLen == 0
                    val plain = seal(Cipher.DECRYPT_MODE, key, prefix, counter, last, header, current, currentLen)
                    digest.update(plain)
                    plainSize += plain.size
                    output.write(plain)
                    if (last) break
                    if (nextLen < TAG_BYTES) throw EOFException("encrypted file is truncated")
                    counter += 1
                    val swap = current
                    current = next
                    next = swap
                    currentLen = nextLen
                }
            }
        }
        return Result(plainSize, digest.digest().toHex())
    }

    private fun seal(
        mode: Int,
        key: ByteArray,
        prefix: ByteArray,
        counter: Int,
        last: Boolean,
        header: ByteArray,
        data: ByteArray,
        length: Int
    ): ByteArray {
        val nonce = ByteBuffer.allocate(12).put(prefix).putInt(counter).put(if (last) 1 else 0).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        cipher.updateAAD(header)
        return cipher.doFinal(data, 0, length)
    }

    private fun fill(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
