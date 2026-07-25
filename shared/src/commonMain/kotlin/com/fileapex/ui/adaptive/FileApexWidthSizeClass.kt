package com.fileapex.ui.adaptive

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Material-style window width classes for FileApex adaptive chrome.
 * Compact: phones / folded foldables. Medium/Expanded: tablets / unfolded.
 */
enum class FileApexWidthSizeClass {
    Compact,
    Medium,
    Expanded
}

fun widthSizeClassFor(maxWidth: Dp): FileApexWidthSizeClass = when {
    maxWidth < 600.dp -> FileApexWidthSizeClass.Compact
    maxWidth < 840.dp -> FileApexWidthSizeClass.Medium
    else -> FileApexWidthSizeClass.Expanded
}

val FileApexWidthSizeClass.isWide: Boolean
    get() = this != FileApexWidthSizeClass.Compact
