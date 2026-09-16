package com.fileapex.cli.dash

import com.fileapex.platform.DesktopPlatformPaths
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.InputStream
import java.util.Scanner

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

    // Windows state
    private var originalInMode: Int = 0
    private var originalOutMode: Int = 0

    private val isWindows = DesktopPlatformPaths.isWindows()

    private var cachedTerminalSize = Pair(80, 24)
    private var lastSizeCheckEpochMs = 0L

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
            enableRawModeWindows()
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
            restoreWindows()
        } else {
            restoreUnix()
        }
    }

    private fun enableRawModeWindows(): Boolean {
        val k32 = kernel32 ?: return false
        val hIn = k32.GetStdHandle(-10) ?: return false // STD_INPUT_HANDLE
        val inMode = IntArray(1)
        if (!k32.GetConsoleMode(hIn, inMode)) return false
        originalInMode = inMode[0]

        // Disable ENABLE_LINE_INPUT (0x0002) and ENABLE_ECHO_INPUT (0x0004)
        val rawInMode = (originalInMode and (0x0002 or 0x0004).inv()) or 0x0200
        k32.SetConsoleMode(hIn, rawInMode)

        val hOut = k32.GetStdHandle(-11) // STD_OUTPUT_HANDLE
        if (hOut != null) {
            val outMode = IntArray(1)
            if (k32.GetConsoleMode(hOut, outMode)) {
                originalOutMode = outMode[0]
                // ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004
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
            // Use stty raw -echo opost so \n continues to map to \r\n and prevents raw staircase indenting
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
            // Line buffered fallback
            val line = runCatching { readLine() }.getOrNull() ?: return null
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return KeyEvent(KeyAction.ENTER)
            return KeyEvent(KeyAction.CHAR, trimmed[0])
        }

        // Non-blocking check to prevent freezing the event loop
        val available = runCatching { input.available() }.getOrDefault(0)
        if (available <= 0) {
            return null
        }

        val b1 = runCatching { input.read() }.getOrDefault(-1)
        if (b1 == -1) return null

        when (b1) {
            3 -> return KeyEvent(KeyAction.CTRL_C)
            10, 13 -> return KeyEvent(KeyAction.ENTER)
            32 -> return KeyEvent(KeyAction.SPACE, ' ')
            127, 8 -> return KeyEvent(KeyAction.BACKSPACE)
            27 -> { // Escape or escape sequence
                if (input.available() > 0) {
                    val b2 = input.read()
                    if (b2 == 91) { // '['
                        val b3 = input.read()
                        return when (b3) {
                            65 -> KeyEvent(KeyAction.UP)
                            66 -> KeyEvent(KeyAction.DOWN)
                            67 -> KeyEvent(KeyAction.RIGHT)
                            68 -> KeyEvent(KeyAction.LEFT)
                            else -> {
                                // Drain any trailing escape sequence characters (e.g. F1-F12 like ESC [ 2 1 ~)
                                while (input.available() > 0) {
                                    val next = input.read()
                                    if (next == 126 || (next in 64..126 && next !in 48..57 && next != 59)) {
                                        break
                                    }
                                }
                                KeyEvent(KeyAction.UNKNOWN)
                            }
                        }
                    } else if (b2 == 79) { // 'O' (SS3 sequences, e.g. F1-F4)
                        if (input.available() > 0) {
                            input.read() // consume single trailing char
                        }
                        return KeyEvent(KeyAction.UNKNOWN)
                    }
                }
                return KeyEvent(KeyAction.ESCAPE)
            }
            else -> {
                val c = b1.toChar()
                return KeyEvent(KeyAction.CHAR, c)
            }
        }
    }

    fun readLinePrompt(promptText: String): String {
        showCursor()
        val wasRaw = rawModeEnabled
        if (wasRaw) {
            if (isWindows) restoreWindows() else restoreUnix()
        }

        print(promptText)
        System.out.flush()
        val input = Scanner(System.`in`).nextLine().trim()

        if (wasRaw) {
            if (isWindows) enableRawModeWindows() else enableRawModeUnix()
            hideCursor()
        }
        return input
    }
}
