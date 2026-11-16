package com.fileapex.domain.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpuNamesTest {
    @Test
    fun theGlRendererStringIsTheName() {
        assertEquals("Adreno (TM) 750", GpuNames.fromGlRenderer("  Adreno (TM) 750 "))
        assertEquals("Mali-G715-Immortalis MC11", GpuNames.fromGlRenderer("Mali-G715-Immortalis MC11"))
    }

    @Test
    fun whitespaceIsCollapsed() {
        assertEquals("Mali-G78 MP14", GpuNames.fromGlRenderer("Mali-G78   MP14\n"))
    }

    @Test
    fun missingOrBlankGivesNull() {
        assertNull(GpuNames.fromGlRenderer(null))
        assertNull(GpuNames.fromGlRenderer("   "))
    }

    @Test
    fun aSoftwareRendererIsNotReportedAsTheDeviceGraphics() {
        assertNull(GpuNames.fromGlRenderer("Android Emulator OpenGL ES Translator (Software)"))
        assertNull(GpuNames.fromGlRenderer("Google SwiftShader"))
    }
}
