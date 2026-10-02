package com.fileapex.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class KineticStyleStorageTest {

    @Test
    fun missingOrUnknownValuesStayOnSpace() {
        assertEquals(KineticStyle.SPACE, KineticStyle.fromStorage(null))
        assertEquals(KineticStyle.SPACE, KineticStyle.fromStorage(""))
        assertEquals(KineticStyle.SPACE, KineticStyle.fromStorage("not-a-style"))
        assertEquals(KineticStyle.FROSTED, KineticStyle.fromStorage("frosted"))
        assertEquals(KineticStyle.JADED_STEEL, KineticStyle.fromStorage("JADED_STEEL"))
    }
}
