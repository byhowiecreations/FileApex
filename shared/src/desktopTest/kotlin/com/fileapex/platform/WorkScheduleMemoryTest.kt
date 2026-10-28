package com.fileapex.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorkScheduleMemoryTest {
    private class MapStore : WorkStateStore {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
    }

    private val store = MapStore()
    private var databaseExists = false
    private val memory = WorkScheduleMemory(store) { databaseExists }

    @Test
    fun aNewInstallWithNothingScheduledNeverNeedsToCancel() {
        assertFalse(memory.cancelNeeded("drive"))
    }

    @Test
    fun anUpdatedInstallThatAlreadyHasWorkManagerStateChecksOnceThenStopsAsking() {
        databaseExists = true
        assertTrue(memory.cancelNeeded("drive"))
        memory.markIdle("drive")
        assertFalse(memory.cancelNeeded("drive"))
    }

    @Test
    fun scheduledWorkIsCancelledOnceAndThenRemembered() {
        memory.markScheduled("backup")
        assertTrue(memory.cancelNeeded("backup"))
        assertTrue("asking is not the same as cancelling", memory.cancelNeeded("backup"))
        memory.markIdle("backup")
        assertFalse(memory.cancelNeeded("backup"))
    }

    @Test
    fun eachFeatureHasItsOwnState() {
        memory.markScheduled("drive")
        assertTrue(memory.cancelNeeded("drive"))
        assertFalse(memory.cancelNeeded("backup"))
    }

    @Test
    fun schedulingAgainAfterIdleIsRemembered() {
        memory.markScheduled("drive")
        memory.markIdle("drive")
        memory.markScheduled("drive")
        assertEquals("scheduled", store.map["drive"])
    }

    @Test
    fun theManifestStopsWorkManagerStartingAtLaunchAndTheApplicationProvidesItsConfiguration() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val manifest = File(root, "composeApp/src/androidMain/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("androidx.work.WorkManagerInitializer") && manifest.contains("tools:node=\"remove\""))
        val app = File(root, "composeApp/src/androidMain/kotlin/com/fileapex/FileApexApplication.kt").readText()
        assertTrue(app.contains("Configuration.Provider"))
    }

    @Test
    fun onlyTheGuardedSchedulersTouchWorkManager() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val users = listOf("shared/src", "composeApp/src").map { File(root, it) }.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" && "Test" !in it.path && "WorkManager.getInstance" in it.readText() }.map { it.name }.toList()
        }.toSet()
        assertEquals(setOf("DriveSyncScheduler.android.kt", "BackupPlatform.android.kt"), users)
    }
}
