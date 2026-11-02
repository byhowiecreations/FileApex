package com.fileapex.cli.ipc

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Largest line either side accepts; a longer one ends the connection instead of growing memory. */
internal const val CLI_IPC_MAX_LINE_BYTES = 1 shl 20

/** One UTF-8 line without its terminator, null at end of stream; [InputStream] should be buffered. */
internal fun InputStream.readBoundedLine(max: Int = CLI_IPC_MAX_LINE_BYTES): String? {
    val line = ByteArrayOutputStream()
    while (true) {
        val b = read()
        if (b < 0) return if (line.size() == 0) null else line.toString(Charsets.UTF_8)
        if (b == '\n'.code) return line.toString(Charsets.UTF_8).removeSuffix("\r")
        if (line.size() >= max) throw IOException("CLI line exceeds $max bytes")
        line.write(b)
    }
}
