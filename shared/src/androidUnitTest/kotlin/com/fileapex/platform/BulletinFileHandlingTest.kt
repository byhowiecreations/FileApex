package com.fileapex.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class BulletinFileHandlingTest {

    @Test
    fun mapsKnownExtensionsToMimeTypes() {
        assertEquals("text/csv", resolveMimeType("data.csv"))
        assertEquals("text/plain", resolveMimeType("server.log"))
        assertEquals("application/json", resolveMimeType("config.json"))
        assertEquals("application/sql", resolveMimeType("schema.sql"))
        assertEquals("application/msword", resolveMimeType("document.doc"))
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", resolveMimeType("report.docx"))
        assertEquals("application/pdf", resolveMimeType("document.pdf"))
        assertEquals("text/plain", resolveMimeType("readme.txt"))
        assertEquals("application/zip", resolveMimeType("archive.zip"))
        assertEquals("application/vnd.android.package-archive", resolveMimeType("app.apk"))
    }

    @Test
    fun handlesCaseInsensitivityAndPaths() {
        assertEquals("text/csv", resolveMimeType("/storage/emulated/0/Download/FileApex/DATA.CSV"))
        assertEquals("text/plain", resolveMimeType("ERROR.LOG"))
        assertEquals("application/json", resolveMimeType("payload.JSON"))
    }

    @Test
    fun fallsBackToWildcardWhenNoExtension() {
        assertEquals("*/*", resolveMimeType("unknown_file"))
        assertEquals("*/*", resolveMimeType(""))
    }
}
