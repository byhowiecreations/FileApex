package com.fileapex.data.bulletin

/** Picks the largest preview (long edge, then JPEG quality) that fits the message budget, so every image gets one. */
object BulletinPreviewFit {
    class Result(val bytes: ByteArray, val edge: Int, val quality: Int)

    private val edges = intArrayOf(320, 240, 160, 96, 64)
    private val qualities = intArrayOf(75, 60, 45, 30)

    fun encode(maxBytes: Int, encode: (edge: Int, quality: Int) -> ByteArray): Result? {
        for (edge in edges) {
            for (quality in qualities) {
                val bytes = encode(edge, quality)
                if (bytes.size <= maxBytes) return Result(bytes, edge, quality)
            }
        }
        return null
    }
}
