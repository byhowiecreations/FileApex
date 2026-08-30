package com.fileapex.platform

import java.awt.Window
import javax.swing.JFrame

object DesktopMacWindowClosePolicy {
    fun install(window: Window) {
        if (!DesktopPlatformPaths.isMacOs()) return
        val frame = window as? JFrame ?: run {
            DesktopLifecycleLog.log("MacWindowClosePolicy: skip — root window is not a JFrame (${window.javaClass.name})")
            return
        }
        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        DesktopLifecycleLog.log("MacWindowClosePolicy: installed DO_NOTHING_ON_CLOSE on main window")
    }
}
