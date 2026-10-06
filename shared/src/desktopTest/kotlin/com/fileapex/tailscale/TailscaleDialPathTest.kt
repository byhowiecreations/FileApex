package com.fileapex.tailscale

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleDialPathTest {
    @Test
    fun lanRequestHasNoDialPreface() {
        val out = ByteArrayOutputStream()
        writeTailscaleDialToken(out, "")
        out.write("GET /api/v1/health".encodeToByteArray())
        assertEquals("GET /api/v1/health", out.toString(Charsets.UTF_8))
    }

    @Test
    fun tailnetRequestPutsTheTokenLineBeforeHttp() {
        val out = ByteArrayOutputStream()
        writeTailscaleDialToken(out, "abc")
        out.write("GET /api/v1/health".encodeToByteArray())
        assertEquals("abc\nGET /api/v1/health", out.toString(Charsets.UTF_8))
    }

    @Test
    fun disabledSwitchDoesNotLoadTheNativeLibrary() {
        val library = File("macos/build/Tsnet/libFileApexTsnet.dylib").takeIf { it.isFile }
            ?: File("../macos/build/Tsnet/libFileApexTsnet.dylib")
        assertTrue(library.isFile)
        assertEquals(TailscalePhase.Off, TailscaleNodeRuntime.state.value.phase)
        assertEquals(0, TailscaleNodeRuntime.activeTailnetConnections())
        assertEquals(emptyList<TailscaleObservedPeer>(), TailscaleNodeRuntime.observedTailnetPeers())
        assertNull(tailscaleLoopbackOrNull("192.168.1.20", 8080))
        assertNull(tailscaleLoopbackOrNull("100.64.1.2", 8080))
        assertFalse(tsnetNativeLibraryLoaded())
    }
}
