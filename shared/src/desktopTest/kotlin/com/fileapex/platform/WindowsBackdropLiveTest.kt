package com.fileapex.platform

import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Runs on a real Windows machine only. A window handle that is not valid still has to reach dwmapi and come back with an error code. */
class WindowsBackdropLiveTest {
    @Test
    fun theDwmCallReachesWindowsInsteadOfFailingInsideJna() {
        assumeTrue(DesktopPlatformPaths.isWindows())
        val result = DesktopWindowsBackdrop.callDwm(null, attribute = 38, value = 2)
        assertNotNull("DwmSetWindowAttribute never ran", result)
    }
}
