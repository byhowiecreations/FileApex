package com.omninode.platform

/** True on desktop (Mac): click selects, ⌘/Ctrl-click multi-selects. Android stays long-press. */
expect fun usesDesktopFileSelection(): Boolean
