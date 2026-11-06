package com.fileapex.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortcutLabelTest {
    @Test
    fun aNormalNameIsKeptAndCutToTheLimit() {
        assertEquals("Moto razr fold", ShortcutLabel.of("Moto razr fold", "Paired Device", 25))
        assertEquals("abcde", ShortcutLabel.of("abcdefgh", "Paired Device", 5))
    }

    @Test
    fun anEmptyOrBlankNameNeverReachesAndroidBecauseItRefusesAnEmptyLabel() {
        assertEquals("Paired Device", ShortcutLabel.of("", "Paired Device", 25))
        assertEquals("Paired Device", ShortcutLabel.of("   ", "Paired Device", 25))
    }

    @Test
    fun theFallbackIsCutToTheLimitToo() {
        assertEquals("Pair", ShortcutLabel.of("", "Paired Device", 4))
    }
}
