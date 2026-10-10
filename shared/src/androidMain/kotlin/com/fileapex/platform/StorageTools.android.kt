package com.fileapex.platform

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import com.fileapex.i18n.AppI18n
import java.io.File

private const val TRASH_INDEX_NAME = ".fileapex-trash-index"
private const val LARGEST_LIMIT = 500
private const val MEDIA_PREFIX = "media:"
private const val FILE_PREFIX = "file:"

private val skippedPathParts = listOf("/.Trash/", "/Android/data/", "/Android/obb/", "/.thumbnails/")

actual fun storageToolsSupported(): Boolean = true

internal fun trashRoots(context: Context): List<File> = listOfNotNull(
    File("/storage/emulated/0/.Trash"),
    context.getExternalFilesDir("trash")
)

actual fun queryStorageUsage(): StorageUsage {
    val stat = StatFs(Environment.getExternalStorageDirectory().path)
    val reported = stat.blockCountLong * stat.blockSizeLong
    val free = stat.availableBlocksLong * stat.blockSizeLong
    val total = advertisedStorageBytes(reported)
    return StorageUsage(totalBytes = total, usedBytes = (total - free).coerceIn(0L, total))
}

actual fun scanStorage(): StorageScan {
    val usage = queryStorageUsage()
    val everything = ArrayList<StorageFileEntry>()
    val context = trashContext
    val indexed = context != null && runCatching { scanMediaStore(context, everything) }.isSuccess
    if (!indexed || everything.isEmpty()) {
        everything.clear()
        walkStorage(File(defaultStorageRoot()), everything)
    }
    val grouped = HashMap<StorageCategory, MutableList<StorageFileEntry>>()
    for (entry in everything) {
        val category = StorageCategory.of(entry.name, entry.mimeType) ?: continue
        grouped.getOrPut(category) { ArrayList() } += entry
    }
    val bySize = compareByDescending<StorageFileEntry> { it.sizeBytes }
    return StorageScan(
        usage = usage,
        byCategory = StorageCategory.entries.associateWith { (grouped[it] ?: emptyList<StorageFileEntry>()).sortedWith(bySize) },
        largest = everything.sortedWith(bySize).take(LARGEST_LIMIT),
        everything = everything
    )
}

private fun skipped(path: String): Boolean = skippedPathParts.any { path.contains(it) }

private fun scanMediaStore(context: Context, out: MutableList<StorageFileEntry>) {
    val projection = arrayOf(
        MediaStore.MediaColumns.DATA,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.DATE_MODIFIED,
    )
    context.contentResolver.query(
        MediaStore.Files.getContentUri("external"),
        projection,
        MediaStore.MediaColumns.SIZE + ">0",
        null,
        null
    )?.use { cursor ->
        while (cursor.moveToNext()) {
            val path = cursor.getString(0) ?: continue
            if (skipped(path) || path.contains("/.")) continue
            val file = File(path)
            if (!file.isFile) continue
            out += StorageFileEntry(
                path = path,
                name = file.name,
                sizeBytes = cursor.getLong(1),
                modifiedMillis = cursor.getLong(3) * 1000L,
                mimeType = cursor.getString(2).orEmpty()
            )
        }
    }
}

private fun walkStorage(root: File, out: MutableList<StorageFileEntry>) {
    root.walkTopDown()
        .onEnter { dir -> !dir.name.startsWith(".") && !skipped(dir.absolutePath + "/") }
        .filter { it.isFile && it.length() > 0 }
        .forEach { file ->
            out += StorageFileEntry(
                path = file.absolutePath,
                name = file.name,
                sizeBytes = file.length(),
                modifiedMillis = file.lastModified(),
                mimeType = java.net.URLConnection.guessContentTypeFromName(file.name).orEmpty()
            )
        }
}

private fun filesCollection(): Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

actual fun listTrashed(): List<TrashedEntry> {
    val context = trashContext ?: return emptyList()
    val entries = ArrayList<TrashedEntry>()
    if (Build.VERSION.SDK_INT >= 30) {
        runCatching {
            val args = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, MediaStore.MediaColumns.SIZE + ">=0")
            }
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.RELATIVE_PATH,
            )
            context.contentResolver.query(filesCollection(), projection, args, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    val relative = cursor.getString(4).orEmpty()
                    entries += TrashedEntry(
                        id = MEDIA_PREFIX + cursor.getLong(0),
                        name = name,
                        sizeBytes = cursor.getLong(2),
                        trashedAtMillis = cursor.getLong(3) * 1000L,
                        originalPath = "/storage/emulated/0/$relative$name",
                        isDirectory = false
                    )
                }
            }
        }
    }
    for (root in trashRoots(context)) {
        val index = readTrashIndex(root)
        root.listFiles().orEmpty()
            .filter { it.name != TRASH_INDEX_NAME }
            .forEach { file ->
                entries += TrashedEntry(
                    id = FILE_PREFIX + file.absolutePath,
                    name = file.name,
                    sizeBytes = if (file.isDirectory) file.walkTopDown().filter { it.isFile }.sumOf { it.length() } else file.length(),
                    trashedAtMillis = index[file.name]?.second ?: file.lastModified(),
                    originalPath = index[file.name]?.first,
                    isDirectory = file.isDirectory
                )
            }
    }
    return entries.sortedByDescending { it.trashedAtMillis }
}

actual fun restoreTrashed(ids: List<String>): Boolean {
    val context = trashContext ?: error(AppI18n.t("trash_failed"))
    val prompted = promptForMedia(context, ids) { uris ->
        MediaStore.createTrashRequest(context.contentResolver, uris, false)
    }
    for (id in ids.filter { it.startsWith(FILE_PREFIX) }) {
        val source = File(id.removePrefix(FILE_PREFIX))
        if (!source.exists()) continue
        val root = source.parentFile ?: continue
        val original = readTrashIndex(root)[source.name]?.first?.let(::File)
        val folder = original?.parentFile ?: File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Restored")
        folder.mkdirs()
        val dest = uniqueTrashDest(folder, original?.name ?: source.name)
        if (!source.renameTo(dest) && !copyThenDelete(source, dest)) error(AppI18n.t("trash_failed"))
        removeTrashOrigin(root, source.name)
    }
    return !prompted
}

actual fun purgeTrashed(ids: List<String>): Boolean {
    val context = trashContext ?: error(AppI18n.t("trash_failed"))
    val prompted = promptForMedia(context, ids) { uris ->
        MediaStore.createDeleteRequest(context.contentResolver, uris)
    }
    for (id in ids.filter { it.startsWith(FILE_PREFIX) }) {
        val file = File(id.removePrefix(FILE_PREFIX))
        if (file.exists() && !file.deleteRecursively()) error(AppI18n.t("trash_failed"))
        file.parentFile?.let { removeTrashOrigin(it, file.name) }
    }
    return !prompted
}

private fun promptForMedia(
    context: Context,
    ids: List<String>,
    build: (List<Uri>) -> android.app.PendingIntent,
): Boolean {
    val uris = ids.filter { it.startsWith(MEDIA_PREFIX) }
        .mapNotNull { it.removePrefix(MEDIA_PREFIX).toLongOrNull() }
        .map { ContentUris.withAppendedId(filesCollection(), it) }
    if (uris.isEmpty()) return false
    if (Build.VERSION.SDK_INT < 30) return false
    val launch = trashPrompt ?: error(AppI18n.t("trash_failed"))
    launch(build(uris).intentSender)
    return true
}

private fun indexFile(root: File) = File(root, TRASH_INDEX_NAME)

/** name -> (original path, trashed-at millis). */
private fun readTrashIndex(root: File): Map<String, Pair<String, Long>> {
    val file = indexFile(root)
    if (!file.isFile) return emptyMap()
    return file.readLines().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 3) null else parts[0] to (parts[1] to (parts[2].toLongOrNull() ?: 0L))
    }.toMap()
}

internal fun recordTrashOrigin(root: File, trashedName: String, originalPath: String) {
    runCatching { indexFile(root).appendText("$trashedName\t$originalPath\t${System.currentTimeMillis()}\n") }
}

private fun removeTrashOrigin(root: File, trashedName: String) {
    runCatching {
        val file = indexFile(root)
        if (!file.isFile) return
        file.writeText(file.readLines().filterNot { it.substringBefore('\t') == trashedName }.joinToString("") { "$it\n" })
    }
}

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
