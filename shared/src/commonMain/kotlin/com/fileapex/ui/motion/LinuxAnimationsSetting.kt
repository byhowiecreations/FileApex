package com.fileapex.ui.motion

object LinuxAnimationsSetting {
    /** `gsettings get org.gnome.desktop.interface enable-animations` prints true or false; anything else means unknown. */
    fun parse(output: String): Boolean? = when (output.trim()) {
        "true" -> true
        "false" -> false
        else -> null
    }
}
