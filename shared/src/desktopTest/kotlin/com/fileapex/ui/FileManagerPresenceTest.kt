package com.fileapex.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FileManagerPresenceTest {
    @Test
    fun `layout width is the real window width until a window move starts`() {
        FileManagerPresence.reset()
        assertEquals(440f, FileManagerPresence.layoutWidthDp(440f), 0.001f)
    }

    @Test
    fun `during a move the shell lays out for the width the window is heading to`() {
        FileManagerPresence.reset()
        FileManagerPresence.windowTargetWidthDp.value = 1100f
        assertEquals("growing: the wide shell from the first frame", 1100f, FileManagerPresence.layoutWidthDp(520f), 0.001f)
        FileManagerPresence.windowTargetWidthDp.value = 440f
        assertEquals("shrinking: the compact shell from the first frame", 440f, FileManagerPresence.layoutWidthDp(900f), 0.001f)
        FileManagerPresence.windowTargetWidthDp.value = null
        assertEquals("settled: the real width again", 900f, FileManagerPresence.layoutWidthDp(900f), 0.001f)
    }

    @Test
    fun `reset clears a move that never reported it had settled`() {
        FileManagerPresence.windowTargetWidthDp.value = 1100f
        FileManagerPresence.reset()
        assertEquals(500f, FileManagerPresence.layoutWidthDp(500f), 0.001f)
    }
}
