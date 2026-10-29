package com.fileapex.util

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream

/** Puts [LogRedactor] in front of `System.out` and `System.err`, which covers every `println` in the app. */
object LogRedaction {
    fun wrap(target: PrintStream): PrintStream = RedactingPrintStream(target)

    @Synchronized
    fun install() {
        if (System.out !is RedactingPrintStream) System.setOut(wrap(System.out))
        if (System.err !is RedactingPrintStream) System.setErr(wrap(System.err))
    }
}

private class RedactingPrintStream(target: PrintStream) : PrintStream(RedactingOutputStream(target), true, "UTF-8")

/**
 * Collects bytes until a newline, so an address written in two pieces is still seen whole, then writes the redacted
 * line. A partial line is released on close or when it grows past [MAX_PARTIAL_BYTES].
 */
private class RedactingOutputStream(private val target: PrintStream) : OutputStream() {
    private val line = ByteArrayOutputStream()

    @Synchronized
    override fun write(b: Int) {
        line.write(b)
        if (b == '\n'.code || line.size() > MAX_PARTIAL_BYTES) emit()
    }

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) write(b[i].toInt())
    }

    @Synchronized
    override fun flush() {
        if (line.size() > MAX_PARTIAL_BYTES) emit()
        target.flush()
    }

    @Synchronized
    override fun close() {
        emit()
        target.flush()
    }

    private fun emit() {
        if (line.size() == 0) return
        val text = line.toString(Charsets.UTF_8.name())
        line.reset()
        target.write(LogRedactor.redact(text).toByteArray(Charsets.UTF_8))
        target.flush()
    }

    private companion object {
        const val MAX_PARTIAL_BYTES = 8192
    }
}
