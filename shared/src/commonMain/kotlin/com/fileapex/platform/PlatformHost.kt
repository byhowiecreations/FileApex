package com.fileapex.platform

import kotlin.math.roundToInt

/** True on macOS/desktop JVM host — used for desktop-only LAN poll loop. */
expect fun isDesktopHost(): Boolean

/** 1000 where the host labels file and disk sizes in decimal MB. 1024 on Windows. */
expect fun hostFileSizeBase(): Int

fun formatHostFileSize(bytes: Long, unitBase: Int = hostFileSizeBase()): String {
    if (bytes <= 0L) return "0 B"
    val base = unitBase.coerceAtLeast(2)
    if (bytes < base) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (unit < units.lastIndex && value >= base) {
        value /= base
        unit++
    }
    var tenths = (value * 10.0).roundToInt()
    if (tenths >= base * 10 && unit < units.lastIndex) {
        value = tenths / 10.0 / base
        unit++
        tenths = (value * 10.0).roundToInt()
    }
    val whole = tenths / 10
    val fraction = tenths % 10
    return if (fraction == 0) "$whole ${units[unit]}" else "$whole.$fraction ${units[unit]}"
}
