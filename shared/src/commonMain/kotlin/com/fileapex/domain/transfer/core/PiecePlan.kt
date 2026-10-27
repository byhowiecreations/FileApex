package com.fileapex.domain.transfer.core

/** How a file of [size] bytes is cut into verified pieces of [pieceBytes]; the last piece may be shorter. */
class PiecePlan(val size: Long, val pieceBytes: Int) {
    /** Memory for one piece, never larger than the file. */
    val bufferBytes: Int get() = minOf(pieceBytes.toLong(), maxOf(size, 1L)).toInt()

    /** Bytes in the piece that starts at [offset]. */
    fun lengthAt(offset: Long): Int = minOf(pieceBytes.toLong(), size - offset).toInt()

    /** Where to continue given a partial file of [partLength] bytes: only whole pieces count, and a longer file starts over. */
    fun resumeOffset(partLength: Long): Long = if (partLength <= size) partLength / pieceBytes * pieceBytes else 0L

    /** True for an offset a peer may ask to continue from. */
    fun isValidOffset(offset: Long): Boolean = offset in 0..size && offset % pieceBytes == 0L
}
