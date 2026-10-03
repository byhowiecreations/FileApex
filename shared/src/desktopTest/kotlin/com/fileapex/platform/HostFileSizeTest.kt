package com.fileapex.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class HostFileSizeTest {

    @Test
    fun macAndAndroidUseDecimalUnitsMatchingFinder() {
        assertEquals(1000, hostFileSizeBase())
        assertEquals("0 B", formatHostFileSize(0, 1000))
        assertEquals("999 B", formatHostFileSize(999, 1000))
        assertEquals("1 KB", formatHostFileSize(1000, 1000))
        assertEquals("10.2 KB", formatHostFileSize(10_244, 1000))
        assertEquals("282.5 KB", formatHostFileSize(282_481, 1000))
        assertEquals("359.1 KB", formatHostFileSize(359_086, 1000))
        assertEquals("17 MB", formatHostFileSize(16_991_235, 1000))
        assertEquals("17 MB", formatHostFileSize(17_012_145, 1000))
        assertEquals("24.4 MB", formatHostFileSize(24_436_679, 1000))
        assertEquals("161.5 MB", formatHostFileSize(161_520_372, 1000))
        assertEquals("163 MB", formatHostFileSize(163_040_139, 1000))
        assertEquals("819.7 MB", formatHostFileSize(819_705_089, 1000))
        assertEquals("1 MB", formatHostFileSize(999_950, 1000))
    }

    @Test
    fun windowsUsesBinaryUnitsMatchingExplorer() {
        assertEquals("1 KB", formatHostFileSize(1024, 1024))
        assertEquals("1 MB", formatHostFileSize(1_048_576, 1024))
        assertEquals("1.5 MB", formatHostFileSize(1_572_864, 1024))
        assertEquals("781.7 MB", formatHostFileSize(819_705_089, 1024))
    }
}
