package com.fileapex.friends

import com.fileapex.platform.WindowsNotifyData
import com.fileapex.platform.WindowsNotifyMessage
import com.fileapex.platform.WindowsNotifyWindowClass
import com.sun.jna.Native
import org.junit.Assert.*
import org.junit.Test

class WindowsNotificationLayoutTest {
    @Test fun nativeStructuresMatchTheWindowsAbi() {
        val wide = Native.POINTER_SIZE == 8
        assertEquals(if (wide) 976 else 956, WindowsNotifyData().size())
        assertEquals(if (wide) 48 else 32, WindowsNotifyMessage().size())
        assertEquals(if (wide) 80 else 48, WindowsNotifyWindowClass().size())
    }

    @Test fun unicodeTextIsTerminatedAndCannotOverflowTheNativeBuffers() {
        val data = WindowsNotifyData()
        data.setText("中".repeat(300), "😀".repeat(300))
        assertEquals(0.toShort(), data.title.last())
        assertEquals(0.toShort(), data.info.last())
        assertEquals('中'.code.toShort(), data.title.first())
        assertFalse(Character.isHighSurrogate(data.info[data.info.lastIndex - 1].toInt().toChar()))
    }
}
