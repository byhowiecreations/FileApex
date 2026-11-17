package com.fileapex.ui.motion

import android.provider.Settings
import com.fileapex.data.settings.androidAppContextOrNull

/** "Remove animations" (and developer options) set the animator duration scale to 0. */
actual fun systemReducedMotion(): Boolean {
    val context = androidAppContextOrNull() ?: return false
    return Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}
