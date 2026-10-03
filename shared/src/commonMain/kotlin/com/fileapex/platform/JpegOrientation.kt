package com.fileapex.platform

/** JPEG EXIF orientation (1 = upright). Non-JPEG bytes return 1. */
fun jpegOrientation(bytes: ByteArray): Int {
    if (bytes.size < 4) return 1
    if (bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return 1
    var offset = 2
    while (offset + 4 < bytes.size) {
        if (bytes[offset] != 0xFF.toByte()) break
        val marker = bytes[offset + 1].toInt() and 0xFF
        if (marker == 0xD8) {
            offset += 2
            continue
        }
        if (marker == 0x01 || (marker in 0xD0..0xD9)) {
            offset += 2
            if (marker == 0xD9 || marker == 0xDA) break
            continue
        }
        val length = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
        if (length < 2 || offset + 2 + length > bytes.size) break
        if (marker == 0xE1) {
            return readExifOrientation(bytes, offset + 4, length - 2)
        }
        offset += 2 + length
    }
    return 1
}

private fun readExifOrientation(bytes: ByteArray, start: Int, length: Int): Int {
    if (length < 14) return 1
    if (start + 6 > bytes.size) return 1
    if (bytes[start] != 'E'.code.toByte() ||
        bytes[start + 1] != 'x'.code.toByte() ||
        bytes[start + 2] != 'i'.code.toByte() ||
        bytes[start + 3] != 'f'.code.toByte()
    ) {
        return 1
    }
    val tiff = start + 6
    if (tiff + 8 > bytes.size) return 1
    val little = bytes[tiff] == 'I'.code.toByte() && bytes[tiff + 1] == 'I'.code.toByte()
    val big = bytes[tiff] == 'M'.code.toByte() && bytes[tiff + 1] == 'M'.code.toByte()
    if (!little && !big) return 1
    fun u16(at: Int): Int {
        if (at + 1 >= bytes.size) return 0
        val a = bytes[at].toInt() and 0xFF
        val b = bytes[at + 1].toInt() and 0xFF
        return if (little) a or (b shl 8) else (a shl 8) or b
    }
    fun u32(at: Int): Int {
        if (at + 3 >= bytes.size) return 0
        val a = bytes[at].toInt() and 0xFF
        val b = bytes[at + 1].toInt() and 0xFF
        val c = bytes[at + 2].toInt() and 0xFF
        val d = bytes[at + 3].toInt() and 0xFF
        return if (little) a or (b shl 8) or (c shl 16) or (d shl 24)
        else (a shl 24) or (b shl 16) or (c shl 8) or d
    }
    val ifdOffset = u32(tiff + 4)
    var entry = tiff + ifdOffset
    if (entry + 2 > bytes.size) return 1
    val count = u16(entry)
    entry += 2
    for (i in 0 until count) {
        val field = entry + i * 12
        if (field + 8 >= bytes.size) return 1
        if (u16(field) == 0x0112) {
            val value = u16(field + 8)
            return if (value in 1..8) value else 1
        }
    }
    return 1
}
