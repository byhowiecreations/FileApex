package com.fileapex.friends.transfer

import com.fileapex.platform.UniqueFileNames
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.FileStore
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal object FriendReceiveFiles {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val publication = Any()
    private val partialName = Regex("\\.fileapex-[0-9a-f]{32}\\.part")
    private class Reservation(val volume: Any, var remaining: Long)
    private val capacity = HashMap<String, Reservation>()

    fun isPartial(name: String): Boolean = partialName.matches(name)

    fun deletePartialsOlderThan(dir: File, cutoffMs: Long) {
        dir.listFiles { f -> isPartial(f.name) && f.lastModified() < cutoffMs }?.forEach { it.delete() }
    }

    fun claim(part: File): Boolean = inFlight.add(part.canonicalPath)
    fun release(part: File) {
        val key = part.canonicalPath
        synchronized(capacity) { capacity.remove(key) }
        inFlight.remove(key)
    }

    /** Android refuses `Files.getFileStore` with a SecurityException, so every file counts as one volume there: safe, it only reserves more. */
    fun reserve(part: File, needed: Long, freeBytes: () -> Long, store: (Path) -> FileStore = Files::getFileStore): Boolean {
        val directory = part.absoluteFile.parentFile ?: throw IOException("Friend partial file has no directory")
        val volume: Any = try { store(directory.toPath()) } catch (_: SecurityException) { "unknown volume" } catch (_: UnsupportedOperationException) { "unknown volume" } catch (_: IOException) { "unknown volume" }
        return synchronized(capacity) {
            var available = (freeBytes().coerceAtLeast(0L) - SPARE).coerceAtLeast(0L)
            capacity.values.filter { it.volume == volume }.forEach {
                if (it.remaining >= available) return@synchronized false
                available -= it.remaining
            }
            if (needed >= available) false else {
                capacity[part.canonicalPath] = Reservation(volume, needed)
                true
            }
        }
    }

    fun written(part: File, remaining: Long) {
        synchronized(capacity) { capacity[part.canonicalPath]?.remaining = remaining.coerceAtLeast(0L) }
    }

    fun publish(part: File, name: String): File = synchronized(publication) {
        val directory = part.absoluteFile.parentFile ?: throw IOException("Friend partial file has no directory")
        val target = File(UniqueFileNames.resolveInDirectory(directory.absolutePath, FriendsWire.safeName(name)))
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        target
    }

    private const val SPARE = 50L * 1024 * 1024
}

internal suspend fun friendFileSha256(file: File): String = withContext(Dispatchers.IO) {
    val context = currentCoroutineContext()
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(FriendsWire.CHUNK)
        while (true) {
            context.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}
