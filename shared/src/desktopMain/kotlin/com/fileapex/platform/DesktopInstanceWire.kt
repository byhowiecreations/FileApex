package com.fileapex.platform

import com.fileapex.security.E2eePrimitives
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class DesktopInstanceDescriptor(val port: Int, val token: String, val version: Int = 1) {
    fun key(): ByteArray = try { Base64.getDecoder().decode(token).also { if (it.size != 32) throw IOException("Invalid app credential") } }
        catch (e: IllegalArgumentException) { throw IOException("Invalid app credential", e) }
}

/** Local credentials are never sent over the socket; the app proves possession before a client sends arguments. */
internal object DesktopInstanceWire {
    const val MAX_FRAME = 256 * 1024
    private val timers = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "FileApex-LocalDeadline").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }

    fun descriptorFile(directory: File) = File(directory, "app-instance.json")

    fun readDescriptor(directory: File): DesktopInstanceDescriptor {
        val file = descriptorFile(directory)
        if (!file.isFile || file.length() > 4096 || !LocalApiFile.ownerOnly(file)) throw IOException("App handoff is not ready")
        val bytes = file.inputStream().use { it.readNBytes(4097) }
        if (bytes.size > 4096) throw IOException("Invalid app descriptor")
        val descriptor = try { Json.decodeFromString<DesktopInstanceDescriptor>(bytes.decodeToString()) }
            catch (e: kotlinx.serialization.SerializationException) { throw IOException("Invalid app descriptor", e) }
        if (descriptor.version != 1 || descriptor.port !in 1..65535) throw IOException("Unsupported app handoff")
        descriptor.key()
        return descriptor
    }

    fun serverAuthenticate(socket: Socket, key: ByteArray, purpose: String): Boolean {
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        val nonce = ByteArray(32).also { input.readFully(it) }
        output.write(proof(key, purpose, "server", nonce)); output.flush()
        val supplied = ByteArray(32).also { input.readFully(it) }
        if (!MessageDigest.isEqual(supplied, proof(key, purpose, "client", nonce))) return false
        output.write(1); output.flush()
        return true
    }

    fun clientAuthenticate(socket: Socket, key: ByteArray, purpose: String): Boolean {
        val nonce = SecureRandom().generateSeed(32)
        val output = socket.getOutputStream()
        val input = DataInputStream(socket.getInputStream())
        output.write(nonce); output.flush()
        val supplied = ByteArray(32).also { input.readFully(it) }
        if (!MessageDigest.isEqual(supplied, proof(key, purpose, "server", nonce))) return false
        output.write(proof(key, purpose, "client", nonce)); output.flush()
        return input.read() == 1
    }

    private fun proof(key: ByteArray, purpose: String, side: String, nonce: ByteArray) =
        E2eePrimitives.hmacSha256(key, "fileapex-local-v1|$purpose|$side|".encodeToByteArray() + nonce)

    fun readFrame(socket: Socket): ByteArray {
        val input = DataInputStream(socket.getInputStream())
        val size = input.readInt()
        if (size !in 1..MAX_FRAME) throw IOException("Invalid local request size")
        return ByteArray(size).also { input.readFully(it) }
    }

    fun writeFrame(socket: Socket, bytes: ByteArray) {
        if (bytes.size !in 1..MAX_FRAME) throw IOException("Local request is too large")
        DataOutputStream(socket.getOutputStream()).apply { writeInt(bytes.size); write(bytes); flush() }
    }

    fun <T> deadline(socket: Socket, action: () -> T): T {
        val timeout = timers.schedule({
            try { socket.close() } catch (e: IOException) { println("DesktopInstance: socket cleanup failed :: ${e::class.simpleName}") }
        }, 3, TimeUnit.SECONDS)
        return try { action() } finally { timeout.cancel(false) }
    }
}
