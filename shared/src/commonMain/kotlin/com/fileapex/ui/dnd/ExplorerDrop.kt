package com.fileapex.ui.dnd

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

const val EXPLORER_DROP_BOX_KEY = "simple-drop-box"
const val EXPLORER_DROP_BOX_DEST = "fileapex-drop-box://target"

object ActiveDrag {
    var sourcePath: String? = null
}

private class DropSpot(
    val key: String,
    val destination: String,
    val bounds: Rect
)

class ExplorerDropHighlight {
    private val spots = ArrayList<DropSpot>()
    var path by mutableStateOf<String?>(null)
        private set

    fun updateSpot(key: String, destination: String, bounds: Rect) {
        if (key.isBlank() || destination.isBlank()) return
        val spot = DropSpot(key, destination, bounds)
        val index = spots.indexOfFirst { it.key == key }
        if (index >= 0) spots[index] = spot else spots.add(spot)
    }

    fun removeSpot(key: String) {
        spots.removeAll { it.key == key }
    }

    fun hoverAt(position: Offset, fallback: String) {
        val hit = spots
            .asSequence()
            .filter { it.bounds.contains(position) }
            .minByOrNull { it.bounds.width * it.bounds.height }
        val next = resolve(hit?.destination ?: fallback)
        if (path != next) path = next
    }

    fun destinationOr(fallback: String): String = resolve(path?.takeIf { it.isNotBlank() } ?: fallback)

    fun clearHover() {
        path = null
    }

    fun clear() {
        path = null
        ActiveDrag.sourcePath = null
    }

    private fun resolve(destination: String): String {
        val source = ActiveDrag.sourcePath
        if (!source.isNullOrBlank() && source == destination) {
            return dropParentOf(destination) ?: destination
        }
        return destination
    }
}

val LocalExplorerDropHighlight = staticCompositionLocalOf { ExplorerDropHighlight() }

val LocalDropParentPath = staticCompositionLocalOf { "" }

fun dropParentOf(path: String): String? {
    val trimmed = path.trimEnd('/', '\\')
    val slash = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
    if (slash <= 0) return null
    return trimmed.substring(0, slash)
}

@Composable
fun Modifier.reportDropSpot(key: String, destinationPath: String): Modifier {
    val highlight = LocalExplorerDropHighlight.current
    if (key.isBlank() || destinationPath.isBlank()) return this
    DisposableEffect(highlight, key) {
        onDispose { highlight.removeSpot(key) }
    }
    return onGloballyPositioned { coordinates ->
        if (coordinates.isAttached) {
            highlight.updateSpot(key, destinationPath, coordinates.boundsInRoot())
        }
    }
}
