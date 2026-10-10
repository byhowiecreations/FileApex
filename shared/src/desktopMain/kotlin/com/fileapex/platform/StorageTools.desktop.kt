package com.fileapex.platform

// Storage tools ship on Android first; the explorer hides the Tools button here.
actual fun storageToolsSupported(): Boolean = false

actual fun queryStorageUsage(): StorageUsage {
    val root = java.io.File(System.getProperty("user.home").orEmpty())
    val total = root.totalSpace
    return StorageUsage(totalBytes = total, usedBytes = (total - root.usableSpace).coerceAtLeast(0L))
}

actual fun scanStorage(): StorageScan =
    StorageScan(queryStorageUsage(), StorageCategory.entries.associateWith { emptyList() }, emptyList())

actual fun listTrashed(): List<TrashedEntry> = emptyList()

actual fun restoreTrashed(ids: List<String>): Boolean = true

actual fun purgeTrashed(ids: List<String>): Boolean = true

actual fun localPathInfo(path: String): LocalPathInfo? {
    val file = java.io.File(path)
    return if (file.exists()) LocalPathInfo(file.isDirectory, if (file.isFile) file.length() else 0L) else null
}

actual fun fileDigest(path: String, maxBytes: Long): String? = runCatching {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    java.io.File(path).inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        var remaining = if (maxBytes < 0) Long.MAX_VALUE else maxBytes
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            md.update(buffer, 0, read)
            remaining -= read
        }
    }
    md.digest().joinToString("") { "%02x".format(it) }
}.getOrNull()
