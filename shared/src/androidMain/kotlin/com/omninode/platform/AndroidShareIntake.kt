package com.omninode.platform

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.omninode.domain.share.IncomingShareFile
import com.omninode.domain.share.IncomingSharePayload
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android system Share (ACTION_SEND / ACTION_SEND_MULTIPLE) → staged files for TransferManager.
 */
object AndroidShareIntake {
    fun isShareAction(intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        return action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE
    }

    fun extractStreamUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(readSingleStream(intent))
            Intent.ACTION_SEND_MULTIPLE -> readMultipleStreams(intent)
            else -> emptyList()
        }
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
        val stagingDir = File(context.cacheDir, "share-staging/$sessionId").also {
            it.mkdirs()
        }
        val resolver = context.contentResolver
        val files = uris.mapIndexed { index, uri ->
            stageOne(resolver, stagingDir, uri, index)
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
        resolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Cannot read shared file: $displayName")
        return IncomingShareFile(
            fileName = dest.name,
            absolutePath = dest.absolutePath,
            sizeBytes = dest.length()
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
}
