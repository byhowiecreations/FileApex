package com.fileapex.structure

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkManagerVersionTest {
    @Test
    fun workManagerIs2120() {
        val location = androidx.work.WorkManager::class.java.protectionDomain?.codeSource?.location?.path
        assertNotNull("work-runtime has no code source location", location)
        assertTrue("work-runtime is at $location", "work-runtime-2.12.0" in location!! || "work-runtime-ktx-2.12.0" in location)
    }
}
