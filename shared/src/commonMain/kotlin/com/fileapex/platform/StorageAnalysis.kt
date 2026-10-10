package com.fileapex.platform

/** Files with identical content. [wastedBytes] is what removing all but one copy would free. */
data class DuplicateGroup(val sizeBytes: Long, val files: List<StorageFileEntry>) {
    val wastedBytes: Long get() = sizeBytes * (files.size - 1)
}

/** [bytes] includes everything below the folder. */
data class FolderUsage(val path: String, val bytes: Long, val fileCount: Int)

private const val DUPLICATE_PREFIX_BYTES = 64L * 1024
private const val DEFAULT_MIN_DUPLICATE_BYTES = 8L * 1024

/**
 * Same size, then same first 64 KB, then same full hash, so unrelated files that merely share a
 * size are never read in full.
 */
fun findDuplicateGroups(
    files: List<StorageFileEntry>,
    digest: (path: String, maxBytes: Long) -> String? = ::fileDigest,
    minBytes: Long = DEFAULT_MIN_DUPLICATE_BYTES,
): List<DuplicateGroup> {
    val sameSize = files.filter { it.sizeBytes >= minBytes }.groupBy { it.sizeBytes }.values.filter { it.size > 1 }
    val groups = ArrayList<DuplicateGroup>()
    for (candidates in sameSize) {
        val size = candidates.first().sizeBytes
        val byPrefix = candidates.groupBy { digest(it.path, DUPLICATE_PREFIX_BYTES) }
        for ((prefix, samePrefix) in byPrefix) {
            if (prefix == null || samePrefix.size < 2) continue
            val byFull = if (size <= DUPLICATE_PREFIX_BYTES) {
                mapOf(prefix to samePrefix)
            } else {
                samePrefix.groupBy { digest(it.path, -1L) }
            }
            for ((full, identical) in byFull) {
                if (full == null || identical.size < 2) continue
                groups += DuplicateGroup(size, identical.sortedBy { it.modifiedMillis })
            }
        }
    }
    return groups.sortedByDescending { it.wastedBytes }
}

/** Cumulative size of every folder under [root], keyed by folder path. */
fun folderUsages(files: List<StorageFileEntry>, root: String): Map<String, FolderUsage> {
    val base = root.trimEnd('/')
    val bytes = HashMap<String, Long>()
    val counts = HashMap<String, Int>()
    for (file in files) {
        var dir = file.path.substringBeforeLast('/', "")
        while (dir.length > base.length && dir.startsWith(base)) {
            bytes[dir] = (bytes[dir] ?: 0L) + file.sizeBytes
            counts[dir] = (counts[dir] ?: 0) + 1
            dir = dir.substringBeforeLast('/', "")
        }
    }
    return bytes.mapValues { (path, size) -> FolderUsage(path, size, counts.getValue(path)) }
}

/** Direct child folders of [parent], largest first. */
fun childFolders(usages: Map<String, FolderUsage>, parent: String): List<FolderUsage> {
    val base = parent.trimEnd('/')
    return usages.values
        .filter { it.path.substringBeforeLast('/') == base }
        .sortedByDescending { it.bytes }
}

enum class CleanupKind { Screenshots, Downloads }

/** Files of [kind] last modified more than [olderThanDays] ago, largest first. */
fun staleFiles(
    files: List<StorageFileEntry>,
    kind: CleanupKind,
    olderThanDays: Int,
    nowMillis: Long,
): List<StorageFileEntry> {
    val cutoff = nowMillis - olderThanDays * 86_400_000L
    return files.filter { file ->
        file.modifiedMillis in 1 until cutoff && when (kind) {
            CleanupKind.Screenshots ->
                file.path.contains("/Screenshots/", ignoreCase = true) ||
                    (file.name.startsWith("Screenshot", ignoreCase = true) &&
                        StorageCategory.of(file.name, file.mimeType) == StorageCategory.Images)
            CleanupKind.Downloads -> file.path.contains("/Download/") || file.path.contains("/Downloads/")
        }
    }.sortedByDescending { it.sizeBytes }
}
