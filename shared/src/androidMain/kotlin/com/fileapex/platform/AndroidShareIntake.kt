package com.fileapex.platform

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.IntentCompat
import android.content.ContentUris
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.fileapex.domain.share.IncomingShareFile
import com.fileapex.domain.share.IncomingSharePayload
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Android system Share (ACTION_SEND / ACTION_SEND_MULTIPLE) → staged files for TransferManager.
 * Resolves direct on-disk file locations when available so files are never unnecessarily duplicated.
 * [isWebPageLinkShare] ignores Chrome page-preview PNGs when [Intent.EXTRA_TEXT] holds the URL.
 */
object AndroidShareIntake {
    fun isShareAction(intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        return action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE || action == Intent.ACTION_PROCESS_TEXT
    }

    fun extractSharedText(intent: Intent?): String? {
        if (intent == null) return null
        val action = intent.action ?: return null
        if (action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!text.isNullOrBlank()) return text.trim()
            val clipItem = intent.clipData?.getItemAt(0)
            val clipText = clipItem?.text?.toString()
            if (!clipText.isNullOrBlank() && !clipText.startsWith("content://")) return clipText.trim()
        } else if (action == Intent.ACTION_PROCESS_TEXT) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            if (!text.isNullOrBlank()) return text.trim()
        }
        return null
    }

    fun isWebPageLinkShare(intent: Intent?): Boolean {
        if (intent == null || intent.action != Intent.ACTION_SEND) return false
        val text = extractSharedText(intent).orEmpty()
        return textContainsWebUrl(text)
    }

    fun extractStreamUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        if (isWebPageLinkShare(intent)) return emptyList()
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> buildList {
                readSingleStream(intent)?.let { add(it) }
                addAll(readClipDataUris(intent))
            }
            Intent.ACTION_SEND_MULTIPLE -> buildList {
                addAll(readMultipleStreams(intent))
                addAll(readClipDataUris(intent))
            }
            else -> emptyList()
        }
        return uris.distinctBy { it.toString() }
    }

    /**
     * Resolves incoming share URIs to direct file paths on storage.
     * FileApex prioritizes referencing the existing file location without duplicating files.
     * Only ephemeral streams without a backing file on disk are staged to app cache.
     */
    suspend fun stageShareUris(
        context: Context,
        uris: List<Uri>
    ): IncomingSharePayload = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty()) { com.fileapex.i18n.AppI18n.t("no_shared_file") }
        val sessionId = UUID.randomUUID().toString()
        val resolver = context.contentResolver
        var stagingDir: File? = null

        val files = coroutineScope {
            uris.mapIndexed { index, uri ->
                async {
                    val directPath = resolveDirectFilePath(context, uri)
                    if (directPath != null) {
                        val directFile = File(directPath)
                        val length = directFile.length()
                        if (length > 0L) {
                            return@async IncomingShareFile(
                                fileName = directFile.name,
                                absolutePath = directFile.absolutePath,
                                sizeBytes = length
                            )
                        }
                    }
                    val dir = stagingDir ?: synchronized(sessionId) {
                        stagingDir ?: File(context.filesDir, "share-staging/$sessionId").also {
                            it.mkdirs()
                            stagingDir = it
                        }
                    }
                    stageOne(resolver, dir, uri, index)
                }
            }.awaitAll()
        }
        IncomingSharePayload(sessionId = sessionId, files = files)
    }

    fun resolveDirectFilePath(context: Context, uri: Uri): String? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path?.takeIf { it.isNotBlank() }
            if (path != null && File(path).let { it.exists() && it.canRead() }) {
                return path
            }
        }
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null

        val resolver = context.contentResolver

        if (DocumentsContract.isDocumentUri(context, uri)) {
            val authority = uri.authority
            val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            if (authority == "com.android.externalstorage.documents" && docId != null) {
                val parts = docId.split(":")
                if (parts.size >= 2) {
                    val type = parts[0]
                    val relativePath = parts[1]
                    val path = if (type.equals("primary", ignoreCase = true)) {
                        "/storage/emulated/0/$relativePath"
                    } else {
                        "/storage/$type/$relativePath"
                    }
                    if (File(path).let { it.exists() && it.canRead() }) return path
                }
            } else if (authority == "com.android.providers.media.documents" && docId != null) {
                val split = docId.split(":")
                if (split.size >= 2) {
                    val type = split[0]
                    val id = split[1]
                    val contentUri = when (type.lowercase()) {
                        "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        else -> MediaStore.Files.getContentUri("external")
                    }
                    val path = queryDataColumn(resolver, contentUri, "${MediaStore.MediaColumns._ID} = ?", arrayOf(id))
                    if (path != null && File(path).let { it.exists() && it.canRead() }) return path
                }
            } else if (authority == "com.android.providers.downloads.documents" && docId != null) {
                if (docId.startsWith("raw:")) {
                    val raw = docId.removePrefix("raw:")
                    if (File(raw).let { it.exists() && it.canRead() }) return raw
                }
                val idLong = docId.toLongOrNull()
                if (idLong != null) {
                    val contentUri = ContentUris.withAppendedId(
                        Uri.parse("content://downloads/public_downloads"),
                        idLong
                    )
                    val path = queryDataColumn(resolver, contentUri, null, null)
                    if (path != null && File(path).let { it.exists() && it.canRead() }) return path
                }
            }
        }

        val mediaPath = queryDataColumn(resolver, uri, null, null)
        if (mediaPath != null && File(mediaPath).let { it.exists() && it.canRead() }) {
            return mediaPath
        }

        val rawPath = uri.path.orEmpty()
        if (rawPath.startsWith("/storage/") || rawPath.startsWith("/sdcard/")) {
            if (File(rawPath).let { it.exists() && it.canRead() }) return rawPath
        }

        return null
    }

    private fun queryDataColumn(
        resolver: ContentResolver,
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): String? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    }.getOrNull()

    private fun stageOne(
        resolver: ContentResolver,
        stagingDir: File,
        uri: Uri,
        index: Int
    ): IncomingShareFile {
        val displayName = queryDisplayName(resolver, uri)
            ?.takeIf { it.isNotBlank() }
            ?: "shared-$index"
        val safeName = sanitizeFileName(displayName)
        var dest = File(stagingDir, safeName)
        if (dest.exists()) {
            val stem = dest.nameWithoutExtension
            val ext = dest.extension
            var n = 1
            do {
                dest = File(
                    stagingDir,
                    if (ext.isEmpty()) "$stem ($n)" else "$stem ($n).$ext"
                )
                n++
            } while (dest.exists())
        }
        val copiedBytes = resolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: error("${com.fileapex.i18n.AppI18n.t("could_not_read_shared_files")}: $displayName")
        check(copiedBytes > 0L) { "Shared file is empty: $displayName" }
        return IncomingShareFile(
            fileName = dest.name,
            absolutePath = dest.absolutePath,
            sizeBytes = copiedBytes
        )
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            return uri.lastPathSegment
        }
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else {
                        null
                    }
                }
        }.getOrNull() ?: uri.lastPathSegment
    }

    private fun sanitizeFileName(raw: String): String {
        val cleaned = raw
            .replace('/', '_')
            .replace('\\', '_')
            .replace('\u0000', '_')
            .trim()
        return cleaned.ifBlank { "shared.bin" }
    }

    private fun readSingleStream(intent: Intent): Uri? {
        return IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
    }

    private fun readMultipleStreams(intent: Intent): List<Uri> {
        return IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    }

    private fun readClipDataUris(intent: Intent): List<Uri> {
        val clip: ClipData = intent.clipData ?: return emptyList()
        return buildList {
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index)?.uri?.let { add(it) }
            }
        }
    }
}
