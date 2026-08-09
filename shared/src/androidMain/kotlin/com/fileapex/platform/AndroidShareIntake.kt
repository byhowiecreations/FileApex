package com.fileapex.platform

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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

    fun extractStreamUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
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
     * Copy content URIs into app cache so TransferManager can send by absolute path.
     * Must run while the temporary grant from the share Intent is still valid.
     */
    suspend fun stageShareUris(
        context: Context,
        uris: List<Uri>
    ): IncomingSharePayload = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty()) { "No shared files" }
        val sessionId = UUID.randomUUID().toString()
        val stagingDir = File(context.filesDir, "share-staging/$sessionId").also {
            it.mkdirs()
        }
        val resolver = context.contentResolver
        val files = coroutineScope {
            uris.mapIndexed { index, uri ->
                async { stageOne(resolver, stagingDir, uri, index) }
            }.awaitAll()
        }
        IncomingSharePayload(sessionId = sessionId, files = files)
    }

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
        } ?: error("Cannot read shared file: $displayName")
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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
    }

    private fun readMultipleStreams(intent: Intent): List<Uri> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
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
