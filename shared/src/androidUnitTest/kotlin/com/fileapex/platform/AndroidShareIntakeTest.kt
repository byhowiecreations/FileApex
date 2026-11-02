package com.fileapex.platform

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidShareIntakeTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private fun register(input: InputStream): Uri = Uri.parse("content://fileapex.intake.test/${UUID.randomUUID()}").also {
        Shadows.shadowOf(context.contentResolver).registerInputStream(it, input)
    }

    @Test fun emptyProviderFilesAreValidStagedFiles() = runBlocking {
        val session = UUID.randomUUID().toString()
        val root = File(context.filesDir, "share-staging/$session")
        try {
            val payload = AndroidShareIntake.stageShareUris(context, listOf(register(ByteArrayInputStream(byteArrayOf()))), session)
            val file = payload.files.single()
            assertEquals(0L, file.sizeBytes)
            assertTrue(File(file.absolutePath).isFile)
        } finally { root.deleteRecursively() }
    }

    @Test fun simultaneousProvidersWithTheSameNameKeepBothFiles() = runBlocking {
        val authority = "fileapex.duplicate.test"
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "application/octet-stream"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
                if (projection?.contains(OpenableColumns.DISPLAY_NAME) == true) MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("same.bin")) } else null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val ready = CountDownLatch(2)
        val uris = listOf("first", "second").map { text ->
            Uri.parse("content://$authority/$text").also { uri ->
                Shadows.shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
                    ready.countDown()
                    check(ready.await(5, TimeUnit.SECONDS))
                    ByteArrayInputStream(text.toByteArray())
                }
            }
        }
        val session = UUID.randomUUID().toString()
        val root = File(context.filesDir, "share-staging/$session")
        try {
            val payload = AndroidShareIntake.stageShareUris(context, uris, session)
            assertEquals(2, payload.files.map { it.absolutePath }.toSet().size)
            assertEquals(setOf("first", "second"), payload.files.map { File(it.absolutePath).readText() }.toSet())
        } finally { root.deleteRecursively() }
    }

    @Test fun failedProviderReadRemovesOnlyItsOwnedSession() = runBlocking {
        val session = UUID.randomUUID().toString()
        val root = File(context.filesDir, "share-staging/$session")
        val kept = File(context.filesDir, "share-staging/keep-${UUID.randomUUID()}").apply { mkdirs(); resolve("keep.bin").writeText("keep") }
        val uri = register(object : InputStream() {
            var reads = 0
            override fun read(): Int = if (reads++ == 0) 1 else throw IOException("test provider lost permission")
        })
        try {
            assertTrue(runCatching { AndroidShareIntake.stageShareUris(context, listOf(uri), session) }.isFailure)
            assertFalse("A failed session must not leave copied files", root.exists())
            assertEquals("keep", kept.resolve("keep.bin").readText())
        } finally { root.deleteRecursively(); kept.deleteRecursively() }
    }

    @Test fun cancelledStagingClosesTheStreamAndRemovesItsCopies() = runBlocking {
        val session = UUID.randomUUID().toString()
        val root = File(context.filesDir, "share-staging/$session")
        var closed = false
        lateinit var staging: Job
        val uri = register(object : InputStream() {
            var read = false
            override fun read(): Int {
                if (read) return -1
                read = true
                staging.cancel()
                return 1
            }
            override fun close() { closed = true }
        })
        try {
            staging = launch(start = CoroutineStart.LAZY) { AndroidShareIntake.stageShareUris(context, listOf(uri), session) }
            staging.start()
            withTimeout(5_000L) { staging.join() }
            assertTrue(closed)
            assertFalse("Cancellation must not leave an owned staging session", root.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun sessionIdsCannotEscapeTheStagingDirectory() = runBlocking {
        val uri = register(ByteArrayInputStream(byteArrayOf(1)))
        val escaped = "escaped-${UUID.randomUUID()}"
        File(context.filesDir, "share-staging").mkdirs()
        try { assertTrue(runCatching { AndroidShareIntake.stageShareUris(context, listOf(uri), "../$escaped") }.isFailure) }
        finally { File(context.filesDir, escaped).deleteRecursively() }
    }
}
