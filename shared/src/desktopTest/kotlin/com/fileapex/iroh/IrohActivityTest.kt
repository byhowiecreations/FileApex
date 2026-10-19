package com.fileapex.iroh

import org.junit.Assert.assertEquals
import org.junit.Test

class IrohActivityTest {
    @Test
    fun namesTheActivityAndKeepsTheStatus() {
        assertEquals("presence check -> 200", IrohActivity.describe("GET /api/v1/health -> 200"))
        assertEquals("file browsing -> 403", IrohActivity.describe("GET /api/v1/files/list -> 403"))
        assertEquals("file transfer -> 200", IrohActivity.describe("POST /api/v1/files/upload-segment -> 200"))
        assertEquals("Bulletin Board message -> 200", IrohActivity.describe("POST /api/v1/bulletin/sync/batch -> 200"))
        assertEquals("Bulletin Board file -> 200", IrohActivity.describe("GET /api/v1/bulletin/file -> 200"))
        assertEquals("device info -> 200", IrohActivity.describe("GET /api/v1/diagnostics -> 200"))
        assertEquals("address exchange -> 200", IrohActivity.describe("POST /api/v1/iroh/address -> 200"))
        assertEquals("find phone -> 200", IrohActivity.describe("POST /api/v1/device/beep -> 200"))
    }

    @Test
    fun unknownPathsStayAsTheyAre() {
        assertEquals("GET /api/v1/other -> 200", IrohActivity.describe("GET /api/v1/other -> 200"))
        assertEquals("?", IrohActivity.describe("?"))
    }
}
