package com.fileapex.platform

import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.fileapex.i18n.AppI18n
import java.io.File
import java.net.URLConnection

internal var trashContext: Context? = null
internal var trashPrompt: ((IntentSender) -> Unit)? = null

fun initAndroidLocalTrash(context: Context) {
    trashContext = context.applicationContext
}

fun bindAndroidTrashPrompt(launch: (IntentSender) -> Unit) {
    trashPrompt = launch
}

actual fun trashLocalEntries(absolutePaths: List<String>): Boolean {
    if (absolutePaths.isEmpty()) return true
    val context = trashContext ?: error(AppI18n.t("trash_failed"))
    val media = mutableListOf<Pair<File, Uri>>()
    val plain = mutableListOf<File>()
    for (path in absolutePaths) {
        val file = File(path)
        if (!file.exists()) continue
        val uri = if (Build.VERSION.SDK_INT >= 30 && !file.isDirectory) mediaStoreUri(context, file) else null
        if (uri != null) media += file to uri else plain += file
    }
    var prompted = false
    if (media.isNotEmpty()) {
        val launch = trashPrompt
        if (launch == null) {
            plain += media.map { it.first }
        } else {
            try {
                val pending = MediaStore.createTrashRequest(
                    context.contentResolver,
                    media.map { it.second },
                    true
                )
                launch(pending.intentSender)
                prompted = true
            } catch (error: RuntimeException) {
                if (error.message?.contains("Media", ignoreCase = true) != true) throw error
                plain += media.map { it.first }
            }
        }
    }
    if (plain.isNotEmpty()) moveIntoDeviceTrash(context, plain)
    return !prompted
}

private fun mediaStoreUri(context: Context, file: File): Uri? {
    val mime = URLConnection.guessContentTypeFromName(file.name)?.lowercase().orEmpty()
    val collection = when {
        mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        mime.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else -> return null
    }
    val projection = arrayOf(MediaStore.MediaColumns._ID)
    val selection = MediaStore.MediaColumns.DATA + "=?"
    context.contentResolver.query(collection, projection, selection, arrayOf(file.absolutePath), null)?.use { cursor ->
        if (!cursor.moveToFirst()) return null
        return ContentUris.withAppendedId(collection, cursor.getLong(0))
    }
    return null
}

private fun moveIntoDeviceTrash(context: Context, files: List<File>) {
    val root = trashRoots(context).firstOrNull { it.exists() || it.mkdirs() }
        ?: error(AppI18n.t("trash_failed"))
    for (file in files) {
        if (!file.exists()) continue
        val original = file.absolutePath
        val dest = uniqueTrashDest(root, file.name)
        val moved = file.renameTo(dest) || copyThenDelete(file, dest)
        if (!moved) error(AppI18n.t("trash_failed"))
        recordTrashOrigin(root, dest.name, original)
    }
}

internal fun uniqueTrashDest(root: File, name: String): File {
    var dest = File(root, name)
    if (!dest.exists()) return dest
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var n = 1
    while (dest.exists()) {
        dest = File(root, "$stem ($n)$ext")
        n++
    }
    return dest
}

internal fun copyThenDelete(source: File, dest: File): Boolean {
    if (source.isDirectory) source.copyRecursively(dest, overwrite = false) else source.copyTo(dest, overwrite = false)
    return source.deleteRecursively()
}

actual fun trashLocalEntriesQuietly(absolutePaths: List<String>) {
    val context = trashContext ?: error(AppI18n.t("trash_failed"))
    moveIntoDeviceTrash(context, absolutePaths.map(::File).filter { it.exists() })
}
