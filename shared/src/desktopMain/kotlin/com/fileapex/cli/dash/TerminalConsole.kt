package com.fileapex.cli.dash

import com.fileapex.platform.DesktopPlatformPaths
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.InputStream
import java.util.Scanner
import org.jline.terminal.Attributes
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.NonBlockingReader

enum class KeyAction {
    UP,
    DOWN,
    LEFT,
    RIGHT,
    ENTER,
    SPACE,
    BACKSPACE,
    ESCAPE,
    CTRL_C,
    CHAR,
    UNKNOWN
}

data class KeyEvent(
    val action: KeyAction,
    val char: Char = ' '
)

object TerminalConsole {
    private var rawModeEnabled = false
    private var shutdownHookInstalled = false

    // Windows / legacy JNA size query
    private var originalInMode: Int = 0
    private var originalOutMode: Int = 0

    private val isWindows = DesktopPlatformPaths.isWindows()

    private var cachedTerminalSize = Pair(80, 24)
    private var lastSizeCheckEpochMs = 0L

    // JLine (Windows): real console input via JNA — System.in.available() is unreliable there.
    private var jlineTerminal: Terminal? = null
    private var jlineSavedAttributes: Attributes? = null
    private var jlineReader: NonBlockingReader? = null

    private interface WinKernel32 : Library {
        fun GetStdHandle(nStdHandle: Int): Pointer?
        fun GetConsoleMode(hConsoleHandle: Pointer, lpMode: IntArray): Boolean
        fun SetConsoleMode(hConsoleHandle: Pointer, dwMode: Int): Boolean
        fun GetConsoleScreenBufferInfo(hConsoleHandle: Pointer, lpConsoleScreenBufferInfo: Pointer): Boolean
    }

    private val kernel32: WinKernel32? by lazy {
        if (isWindows) {
            runCatching { Native.load("kernel32", WinKernel32::class.java) }.getOrNull()
        } else {
            null
        }
    }

    fun enableRawMode(): Boolean {
        if (rawModeEnabled) return true

        if (!shutdownHookInstalled) {
            Runtime.getRuntime().addShutdownHook(Thread {
                restoreTerminal()
            })
            shutdownHookInstalled = true
        }

        rawModeEnabled = if (isWindows) {
            enableRawModeWindowsJline() || enableRawModeWindows()
        } else {
            enableRawModeUnix()
        }
        return rawModeEnabled
    }

    fun restoreTerminal() {
        if (!rawModeEnabled) return
        rawModeEnabled = false

        showCursor()
        if (isWindows) {
            restoreWindowsJline()
            restoreWindows()
        } else {
            restoreUnix()
        }
    }

    private fun enableRawModeWindowsJline(): Boolean {
        return runCatching {
            val terminal = TerminalBuilder.builder()
                .system(true)
                .jna(true)
                .jansi(false)
                .dumb(false)
                .build()
            val saved = terminal.enterRawMode()
            jlineTerminal = terminal
            jlineSavedAttributes = saved
            jlineReader = terminal.reader()
            // Keep VT processing on stdout for ANSI TUI drawing.
            val k32 = kernel32
            val hOut = k32?.GetStdHandle(-11)
            if (k32 != null && hOut != null) {
                val outMode = IntArray(1)
                if (k32.GetConsoleMode(hOut, outMode)) {
                    originalOutMode = outMode[0]
                    k32.SetConsoleMode(hOut, originalOutMode or 0x0004)
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun restoreWindowsJline() {
        runCatching {
            val terminal = jlineTerminal
            val saved = jlineSavedAttributes
            if (terminal != null && saved != null) {
                terminal.setAttributes(saved)
            }
            terminal?.close()
        }
        jlineReader = null
        jlineSavedAttributes = null
        jlineTerminal = null
    }

    private fun enableRawModeWindows(): Boolean {
        val k32 = kernel32 ?: return false
        val hIn = k32.GetStdHandle(-10) ?: return false
        val inMode = IntArray(1)
        if (!k32.GetConsoleMode(hIn, inMode)) return false
        originalInMode = inMode[0]

        val enableExtendedFlags = 0x0080
        val enableQuickEdit = 0x0040
        val rawInMode = (originalInMode and (0x0002 or 0x0004 or enableQuickEdit).inv()) or
            0x0200 or enableExtendedFlags
        k32.SetConsoleMode(hIn, rawInMode)

        val hOut = k32.GetStdHandle(-11)
        if (hOut != null) {
            val outMode = IntArray(1)
            if (k32.GetConsoleMode(hOut, outMode)) {
                originalOutMode = outMode[0]
                k32.SetConsoleMode(hOut, originalOutMode or 0x0004)
            }
        }
        return true
    }

    private fun restoreWindows() {
        val k32 = kernel32 ?: return
        if (originalInMode != 0) {
            val hIn = k32.GetStdHandle(-10)
            if (hIn != null) k32.SetConsoleMode(hIn, originalInMode)
        }
        if (originalOutMode != 0) {
            val hOut = k32.GetStdHandle(-11)
            if (hOut != null) k32.SetConsoleMode(hOut, originalOutMode)
        }
    }

    private fun enableRawModeUnix(): Boolean {
        return runCatching {
            val pb = ProcessBuilder("/bin/sh", "-c", "stty raw -echo opost < /dev/tty").inheritIO()
            val p = pb.start()
            p.waitFor() == 0
        }.getOrDefault(false)
    }

    private fun restoreUnix() {
        runCatching {
            val pb = ProcessBuilder("/bin/sh", "-c", "stty sane < /dev/tty").inheritIO()
            val p = pb.start()
            p.waitFor()
        }
    }

    fun hideCursor() {
        print("\u001b[?25l")
        System.out.flush()
    }

    fun showCursor() {
        print("\u001b[?25h")
        System.out.flush()
    }

    fun clearScreen() {
        print("\u001b[2J\u001b[H")
        System.out.flush()
    }

    fun getTerminalSize(): Pair<Int, Int> {
        val now = System.currentTimeMillis()
        if (now - lastSizeCheckEpochMs < 1000L && cachedTerminalSize.first > 0) {
            return cachedTerminalSize
        }
        lastSizeCheckEpochMs = now

        val jlineSize = jlineTerminal?.size
        if (jlineSize != null && jlineSize.columns > 0 && jlineSize.rows > 0) {
            cachedTerminalSize = Pair(
                jlineSize.columns.coerceAtLeast(40),
                jlineSize.rows.coerceAtLeast(15)
            )
            return cachedTerminalSize
        }

        cachedTerminalSize = if (isWindows) {
            queryWindowsTerminalSize()
        } else {
            queryUnixTerminalSize()
        }
        return cachedTerminalSize
    }

    private fun queryWindowsTerminalSize(): Pair<Int, Int> {
        val k32 = kernel32
        if (k32 != null) {
            val hOut = k32.GetStdHandle(-11)
            if (hOut != null) {
                val buf = Memory(22)
                if (k32.GetConsoleScreenBufferInfo(hOut, buf)) {
                    val left = buf.getShort(10).toInt()
                    val top = buf.getShort(12).toInt()
                    val right = buf.getShort(14).toInt()
                    val bottom = buf.getShort(16).toInt()
                    val cols = (right - left + 1).coerceAtLeast(40)
                    val rows = (bottom - top + 1).coerceAtLeast(15)
                    return Pair(cols, rows)
                }
            }
        }
        return Pair(80, 24)
    }

    private fun queryUnixTerminalSize(): Pair<Int, Int> {
        val sttyOutput = runCatching {
            val p = ProcessBuilder("/bin/sh", "-c", "stty size < /dev/tty").start()
            val text = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            text
        }.getOrNull()

        if (!sttyOutput.isNullOrBlank()) {
            val parts = sttyOutput.split("\\s+".toRegex())
            if (parts.size >= 2) {
                val rows = parts[0].toIntOrNull()
                val cols = parts[1].toIntOrNull()
                if (rows != null && cols != null) {
                    return Pair(cols.coerceAtLeast(40), rows.coerceAtLeast(15))
                }
            }
        }

        val envCols = System.getenv("COLUMNS")?.toIntOrNull()
        val envRows = System.getenv("LINES")?.toIntOrNull()
        if (envCols != null && envRows != null) {
            return Pair(envCols.coerceAtLeast(40), envRows.coerceAtLeast(15))
        }
        return Pair(80, 24)
    }

    fun readKey(input: InputStream = System.`in`): KeyEvent? {
        if (!rawModeEnabled) {
            val line = runCatching { readLine() }.getOrNull() ?: return null
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return KeyEvent(KeyAction.ENTER)
            return KeyEvent(KeyAction.CHAR, trimmed[0])
        }

        val reader = jlineReader
        if (reader != null) {
            return readKeyJline(reader)
        }

        val available = runCatching { input.available() }.getOrDefault(0)
        if (available <= 0) {
            return null
        }

        val b1 = runCatching { input.read() }.getOrDefault(-1)
        if (b1 == -1) return null
        return decodeKeyByte(b1, input)
    }

    private fun readKeyJline(reader: NonBlockingReader): KeyEvent? {
        // NonBlockingReader.READ_EXPIRED == -2
        val b1 = runCatching { reader.read(15L) }.getOrDefault(NonBlockingReader.READ_EXPIRED)
        if (b1 == NonBlockingReader.READ_EXPIRED || b1 < 0) {
            return null
        }
        return decodeKeyByte(b1) { peekMs ->
            runCatching { reader.read(peekMs) }.getOrDefault(NonBlockingReader.READ_EXPIRED)
                .takeIf { it >= 0 }
        }
    }

    private fun decodeKeyByte(b1: Int, input: InputStream): KeyEvent {
        return decodeKeyByte(b1) { _ ->
            if (input.available() > 0) input.read().takeIf { it >= 0 } else null
        }
    }

    private fun decodeKeyByte(b1: Int, readNext: (timeoutMs: Long) -> Int?): KeyEvent {
        when (b1) {
            3 -> return KeyEvent(KeyAction.CTRL_C)
            10, 13 -> return KeyEvent(KeyAction.ENTER)
            32 -> return KeyEvent(KeyAction.SPACE, ' ')
            127, 8 -> return KeyEvent(KeyAction.BACKSPACE)
            27 -> {
                val b2 = readNext(5L) ?: return KeyEvent(KeyAction.ESCAPE)
                if (b2 == 91) {
                    val b3 = readNext(5L) ?: return KeyEvent(KeyAction.UNKNOWN)
                    return when (b3) {
                        65 -> KeyEvent(KeyAction.UP)
                        66 -> KeyEvent(KeyAction.DOWN)
                        67 -> KeyEvent(KeyAction.RIGHT)
                        68 -> KeyEvent(KeyAction.LEFT)
                        else -> {
                            var next = readNext(1L)
                            while (next != null) {
                                if (next == 126 || (next in 64..126 && next !in 48..57 && next != 59)) {
                                    break
                                }
                                next = readNext(1L)
                            }
                            KeyEvent(KeyAction.UNKNOWN)
                        }
                    }
                }
                if (b2 == 79) {
                    readNext(5L)
                    return KeyEvent(KeyAction.UNKNOWN)
                }
                return KeyEvent(KeyAction.ESCAPE)
            }
            else -> return KeyEvent(KeyAction.CHAR, b1.toChar())
        }
    }

    fun readLinePrompt(promptText: String): String {
        showCursor()
        val wasRaw = rawModeEnabled
        val jline = jlineTerminal
        val saved = jlineSavedAttributes

        if (wasRaw) {
            if (jline != null && saved != null) {
                runCatching { jline.setAttributes(saved) }
            } else if (isWindows) {
                restoreWindows()
            } else {
                restoreUnix()
            }
        }

        print(promptText)
        System.out.flush()
        val input = Scanner(System.`in`).nextLine().trim()

        if (wasRaw) {
            if (jline != null) {
                jlineSavedAttributes = jline.enterRawMode()
                jlineReader = jline.reader()
            } else if (isWindows) {
                enableRawModeWindows()
            } else {
                enableRawModeUnix()
            }
            hideCursor()
        }
        return input
    }
}
