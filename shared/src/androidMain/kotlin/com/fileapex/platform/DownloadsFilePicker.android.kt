package com.fileapex.platform

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class OpenDocumentFromDownloads : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: android.content.Context, input: Array<String>): Intent {
        val intent = super.createIntent(context, input)
        val downloads = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Download"
        )
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloads)
        return intent
    }
}

@Composable
actual fun rememberDownloadsFilePicker(
    onPicked: (PickedLocalFile?) -> Unit
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(OpenDocumentFromDownloads()) { uri ->
        if (uri == null) {
            onPicked(null)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val picked = withContext(Dispatchers.IO) {
                copyUriToCache(context, uri)
            }
            onPicked(picked)
        }
    }
    return remember(launcher) {
        {
            launcher.launch(arrayOf("*/*"))
        }
    }
}

private fun copyUriToCache(context: android.content.Context, uri: Uri): PickedLocalFile? {
    val resolver = context.contentResolver
    var displayName = "attachment"
    var sizeBytes = -1L
    resolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (cursor.moveToFirst()) {
            if (nameIndex >= 0) displayName = cursor.getString(nameIndex) ?: displayName
            if (sizeIndex >= 0) sizeBytes = cursor.getLong(sizeIndex)
        }
    }
    val cacheDir = File(context.cacheDir, "note-attachments").apply { mkdirs() }
    val dest = File(cacheDir, displayName)
    resolver.openInputStream(uri)?.use { input ->
        dest.outputStream().use { output -> input.copyTo(output) }
    } ?: return null
    if (sizeBytes <= 0L) sizeBytes = dest.length()
    return PickedLocalFile(
        displayName = displayName,
        sizeBytes = sizeBytes,
        absolutePath = dest.absolutePath
    )
}
