package com.fileapex.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class RedactingPrintStreamTest {
    private val sink = ByteArrayOutputStream()
    private val stream = LogRedaction.wrap(PrintStream(sink, true, "UTF-8"))
    private val savedOut = System.out
    private val savedErr = System.err

    @After
    fun restore() {
        System.setOut(savedOut)
        System.setErr(savedErr)
    }

    private fun written() = sink.toString("UTF-8")

    @Test
    fun printlnIsRedactedBeforeItReachesTheTarget() {
        stream.println("probe 192.168.1.130:8080 timed out for /Users/someone/x.txt")
        assertEquals("probe .130 timed out for [path]\n", written().replace("\r\n", "\n"))
    }

    @Test
    fun anAddressSplitAcrossTwoPrintsIsStillCaught() {
        stream.print("target 192.168.")
        stream.print("1.130 refused")
        stream.println()
        assertEquals("target .130 refused\n", written().replace("\r\n", "\n"))
    }

    @Test
    fun severalLinesInOneWriteAreEachRedacted() {
        stream.print("a 10.0.0.1\nb 10.0.0.2\n")
        assertEquals("a .1\nb .2\n", written().replace("\r\n", "\n"))
    }

    @Test
    fun aPartialLineWaitsForItsNewlineAndIsNotLostOnClose() {
        stream.print("no newline yet 10.0.0.9")
        assertEquals("", written())
        stream.close()
        assertEquals("no newline yet .9", written())
    }

    @Test
    fun multiByteTextSurvives() {
        stream.println("名前 ñ 10.1.2.3")
        assertEquals("名前 ñ .3\n", written().replace("\r\n", "\n"))
    }

    @Test
    fun installingTwiceWrapsOnlyOnce() {
        LogRedaction.install()
        val first = System.out
        LogRedaction.install()
        assertSame(first, System.out)
    }
}
