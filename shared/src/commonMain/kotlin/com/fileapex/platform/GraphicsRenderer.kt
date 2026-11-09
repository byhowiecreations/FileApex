package com.fileapex.platform

/** The saved Windows renderer choice: `automatic`, `direct3d` or `opengl`. Other platforms have no choice and report `automatic`. */
expect fun graphicsRendererChoice(): String

/** Saves the choice; it applies the next time the app starts. A no-op where there is no choice. */
expect fun setGraphicsRendererChoice(choice: String)
