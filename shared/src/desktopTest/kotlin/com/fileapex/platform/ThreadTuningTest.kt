package com.fileapex.platform

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ThreadTuningTest {
    private val key = ThreadTuning.SCHEDULER_KEEP_ALIVE_KEY
    private var saved: String? = null

    @Before
    fun save() {
        saved = System.getProperty(key)
        System.clearProperty(key)
    }

    @After
    fun restore() {
        if (saved == null) System.clearProperty(key) else System.setProperty(key, saved!!)
    }

    @Test
    fun idleCoroutineWorkersAreGivenFiveSecondsInsteadOfSixty() {
        ThreadTuning.apply()
        assertEquals("5", System.getProperty(key))
    }

    @Test
    fun anExplicitSettingIsNeverOverwritten() {
        System.setProperty(key, "30")
        ThreadTuning.apply()
        assertEquals("30", System.getProperty(key))
    }

    @Test
    fun theKeyIsTheOneTheCoroutineSchedulerReads() {
        assertEquals("kotlinx.coroutines.scheduler.keep.alive.sec", key)
    }

    @Test
    fun theApplicationSetsTheKeepAliveBeforeAnythingThatCouldStartTheCoroutinePool() {
        val root = java.io.File("").absoluteFile.let { f -> generateSequence(f) { it.parentFile }.first { java.io.File(it, "settings.gradle.kts").exists() } }
        val source = java.io.File(root, "composeApp/src/androidMain/kotlin/com/fileapex/FileApexApplication.kt").readText()
        val apply = source.indexOf("ThreadTuning.apply()")
        assertTrue(apply > 0)
        assertTrue("the scheduler reads the property once, when it first starts", apply < source.indexOf("Configuration.Builder()"))
    }
}
