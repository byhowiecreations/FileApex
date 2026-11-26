package com.fileapex.ui.explorer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.fileapex.domain.model.RemoteFileItem

/** The parts of the file viewer that are plain arithmetic and list handling. */
internal object FileViewerLogic {
    const val MAX_SCALE = 8f

    data class Neighbours(val previous: RemoteFileItem?, val next: RemoteFileItem?)

    private fun files(list: List<RemoteFileItem>) = list.filter { !it.isDirectory }

    /** The files before and after [current] in [list], skipping folders. */
    fun neighbours(list: List<RemoteFileItem>, current: RemoteFileItem): Neighbours {
        val files = files(list)
        val index = files.indexOfFirst { it.absolutePath == current.absolutePath }
        if (index < 0) return Neighbours(null, null)
        return Neighbours(files.getOrNull(index - 1), files.getOrNull(index + 1))
    }

    /** One based position of [current] among the files, and how many files there are; null when it is not listed. */
    fun position(list: List<RemoteFileItem>, current: RemoteFileItem): Pair<Int, Int>? {
        val files = files(list)
        val index = files.indexOfFirst { it.absolutePath == current.absolutePath }
        return if (index < 0) null else (index + 1) to files.size
    }

    enum class Swipe { Previous, Next }

    /** Dragging the content right goes to the previous file and left to the next; a short drag does nothing. */
    fun swipe(totalDragPx: Float, thresholdPx: Float): Swipe? = when {
        totalDragPx >= thresholdPx -> Swipe.Previous
        totalDragPx <= -thresholdPx -> Swipe.Next
        else -> null
    }

    fun clampScale(scale: Float): Float = scale.coerceIn(1f, MAX_SCALE)

    fun canSwipeImage(scale: Float): Boolean = scale <= 1.01f

    /** Keeps a zoomed image covering the viewport: it can move only by the size it gained from zooming. */
    fun clampOffset(offset: Offset, scale: Float, viewport: Size): Offset {
        if (scale <= 1f) return Offset.Zero
        val maxX = viewport.width * (scale - 1f) / 2f
        val maxY = viewport.height * (scale - 1f) / 2f
        return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }

    /** Splits on any line ending; a final newline does not add an empty line. */
    fun lines(text: String): List<String> {
        if (text.isEmpty()) return listOf("")
        val parts = text.split("\r\n", "\n", "\r")
        return if (parts.size > 1 && parts.last().isEmpty()) parts.dropLast(1) else parts
    }

    fun gutterDigits(lineCount: Int): Int = lineCount.coerceAtLeast(1).toString().length
}
