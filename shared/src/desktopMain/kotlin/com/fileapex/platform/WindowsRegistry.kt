package com.fileapex.platform

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference

/** Reads values under HKEY_LOCAL_MACHINE. [WindowsRegistry] is the real one; tests pass their own. */
internal fun interface WindowsRegistryReader {
    fun readLocalMachineString(subKey: String, name: String): String?

    /** A DWORD or QWORD value, unsigned, or null when the value is missing or another type. */
    fun readLocalMachineNumber(subKey: String, name: String): Long? = null
}

/**
 * Registry reads through advapi32, so no `reg.exe` process is started. Only meaningful on Windows.
 * Names go in as [WString]: JNA's `String` with a UTF-16 encoding leaves the second terminator byte unset, so lookups failed at random.
 */
internal object WindowsRegistry : WindowsRegistryReader {
    private const val ERROR_SUCCESS = 0
    private const val RRF_RT_REG_SZ = 0x00000002
    private const val RRF_RT_REG_NUMBER = 0x00000050
    private const val REG_DWORD = 4
    private const val REG_QWORD = 11
    private const val RRF_SUBKEY_WOW6464KEY = 0x00010000
    private const val MAX_VALUE_BYTES = 4096
    /** 0x80000002 sign-extended to the 64-bit handle width, as winreg.h defines it. */
    private val HKEY_LOCAL_MACHINE: Pointer = Pointer.createConstant(0x80000002L.toInt().toLong())

    private interface Advapi32 : Library {
        fun RegGetValueW(
            hkey: Pointer,
            subKey: WString,
            value: WString,
            flags: Int,
            type: IntByReference?,
            data: Pointer?,
            size: IntByReference
        ): Int
    }

    private val advapi: Advapi32? by lazy {
        if (!DesktopPlatformPaths.isWindows()) null
        else Native.load("advapi32", Advapi32::class.java)
    }

    override fun readLocalMachineString(subKey: String, name: String): String? {
        val api = advapi ?: return null
        val buffer = Memory(MAX_VALUE_BYTES.toLong())
        val size = IntByReference(MAX_VALUE_BYTES)
        val status = api.RegGetValueW(HKEY_LOCAL_MACHINE, WString(subKey), WString(name), RRF_RT_REG_SZ or RRF_SUBKEY_WOW6464KEY, null, buffer, size)
        if (status != ERROR_SUCCESS) return null
        return buffer.getWideString(0).trim().takeIf { it.isNotEmpty() }
    }

    override fun readLocalMachineNumber(subKey: String, name: String): Long? {
        val api = advapi ?: return null
        val buffer = Memory(8)
        val size = IntByReference(8)
        val type = IntByReference(0)
        val status = api.RegGetValueW(HKEY_LOCAL_MACHINE, WString(subKey), WString(name), RRF_RT_REG_NUMBER or RRF_SUBKEY_WOW6464KEY, type, buffer, size)
        if (status != ERROR_SUCCESS) return null
        return when (type.value) {
            REG_DWORD -> buffer.getInt(0).toLong() and 0xFFFFFFFFL
            REG_QWORD -> buffer.getLong(0)
            else -> null
        }
    }
}
