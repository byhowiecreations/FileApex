package com.fileapex.platform

import android.content.ComponentCallbacks2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTrimLevelTest {
    @Test fun uiHiddenAndTheBackgroundLevelsTrim() {
        assertTrue(trimsMemoryAt(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertTrue(trimsMemoryAt(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
    }

    @Test fun theForegroundLevelsAndroidNoLongerDeliversDoNotTrim() {
        assertFalse(trimsMemoryAt(10))
        assertFalse(trimsMemoryAt(15))
    }
}
