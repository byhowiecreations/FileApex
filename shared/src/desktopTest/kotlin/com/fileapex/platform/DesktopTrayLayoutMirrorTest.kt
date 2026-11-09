package com.fileapex.platform

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Mac tray is SwiftUI and the Windows tray is Compose, so the layout numbers are written twice. This keeps the two copies equal. */
class DesktopTrayLayoutMirrorTest {
    private fun swiftSource(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "macos/Tray/TrayMenuView.swift").exists()) dir = dir.parentFile
        return File(requireNotNull(dir) { "macos/Tray not found above ${File("").absolutePath}" }, "macos/Tray/TrayMenuView.swift").readText()
    }

    private fun assertSwiftHas(text: String) {
        assertTrue("TrayMenuView.swift is missing: $text", swiftSource().contains(text))
    }

    @Test
    fun theMacTrayUsesTheSameColumnAndRowSizesAsWindows() {
        assertSwiftHas("static let columnWidth: CGFloat = $TRAY_COLUMN_WIDTH_DP")
        assertSwiftHas("static let rowHeight: CGFloat = $TRAY_ROW_HEIGHT_DP")
        assertSwiftHas("static let chromeHeight: CGFloat = $TRAY_CHROME_HEIGHT_DP")
        assertSwiftHas("static let columnGap: CGFloat = $TRAY_COLUMN_GAP_DP")
        assertSwiftHas("static let padding: CGFloat = $TRAY_PADDING_DP")
        assertSwiftHas("static let maxColumns = $TRAY_MAX_COLUMNS")
    }

    @Test
    fun theMacTrayCountsColumnsTheSameWay() {
        assertSwiftHas("let usable = availableHeight - TrayLayout.chromeHeight")
        assertSwiftHas("usable >= TrayLayout.rowHeight ? Int(usable / TrayLayout.rowHeight) : 1")
        assertSwiftHas("(rowCount + perColumn - 1) / perColumn")
    }
}
