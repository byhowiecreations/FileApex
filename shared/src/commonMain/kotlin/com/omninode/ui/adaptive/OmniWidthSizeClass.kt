package com.omninode.ui.adaptive

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Material-style window width classes for OmniNode adaptive chrome.
 * Compact: phones / folded foldables. Medium/Expanded: tablets / unfolded.
 */
enum class OmniWidthSizeClass {
    Compact,
    Medium,
    Expanded
}

fun widthSizeClassFor(maxWidth: Dp): OmniWidthSizeClass = when {
    maxWidth < 600.dp -> OmniWidthSizeClass.Compact
    maxWidth < 840.dp -> OmniWidthSizeClass.Medium
    else -> OmniWidthSizeClass.Expanded
}

val OmniWidthSizeClass.isWide: Boolean
    get() = this != OmniWidthSizeClass.Compact
