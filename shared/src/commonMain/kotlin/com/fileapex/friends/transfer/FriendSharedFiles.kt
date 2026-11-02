package com.fileapex.friends.transfer

import com.fileapex.friends.FriendsHub
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class FriendSharedQueueException(val acceptedFriends: Set<String>, cause: IOException) : IOException("Could not queue all selected friends", cause)

internal suspend fun enqueueSharedFriendFiles(friendKeys: Collection<String>, files: List<File>): Set<String> =
    withContext(Dispatchers.IO) {
        val accepted = linkedSetOf<String>()
        try { friendKeys.distinct().forEach { key ->
            val copies = mutableListOf<File>()
            var queued = false
            try {
                files.forEach { source ->
                    val dir = File(File(System.getProperty("java.io.tmpdir") ?: ".", "fileapex-friends-outbox"), SecureRandom().nextLong().toString(16))
                    if (!dir.mkdirs()) throw IOException("Could not create friend share staging")
                    val target = File(dir, source.name)
                    copies += target
                    source.inputStream().use { input -> target.outputStream().use { output -> copyFriendSharedSource(input, output) } }
                }
                queued = FriendsHub.enqueueSend(key, copies, owned = true)
                if (queued) accepted += key
            } finally {
                if (!queued) copies.forEach(::deleteOwnedFriendFile)
            }
        } } catch (e: IOException) { throw FriendSharedQueueException(accepted.toSet(), e) }
        accepted
    }

internal suspend fun copyFriendSharedSource(input: InputStream, output: OutputStream) {
    val context = currentCoroutineContext()
    val buffer = ByteArray(32 * 1024)
    while (true) {
        context.ensureActive()
        val count = input.read(buffer)
        context.ensureActive()
        if (count < 0) return
        output.write(buffer, 0, count)
    }
}

internal fun deleteOwnedFriendFile(file: File) {
    if (file.exists() && !file.delete()) throw IOException("Could not delete friend share staging")
    file.parentFile?.let { dir -> if (dir.list()?.isEmpty() == true && !dir.delete()) throw IOException("Could not remove friend share staging folder") }
}
