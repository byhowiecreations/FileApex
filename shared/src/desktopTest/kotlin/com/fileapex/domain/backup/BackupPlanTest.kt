package com.fileapex.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPlanTest {
    private fun file(path: String, size: Long = 10, modified: Long = 100) =
        BackupCandidate(path, "Docs/" + path.substringAfterLast('/'), size, modified)

    @Test
    fun unchangedFilesAreNeverSentAgain() {
        val manifest = BackupManifest.inMemory()
        val a = file("/s/Docs/a.txt")
        manifest.record(a)
        val plan = BackupPlan.build(listOf(a), manifest)
        assertEquals(0, plan.total)
    }

    @Test
    fun newAndChangedFilesAreSeparated() {
        val manifest = BackupManifest.inMemory()
        val old = file("/s/Docs/a.txt")
        manifest.record(old)
        val edited = old.copy(sizeBytes = 11)
        val touched = old.copy(modifiedMillis = 200)
        val fresh = file("/s/Docs/new.txt")
        val plan = BackupPlan.build(listOf(edited, fresh), manifest)
        assertEquals(listOf(fresh), plan.newFiles)
        assertEquals(listOf(edited), plan.changedFiles)
        assertEquals(listOf(touched), BackupPlan.build(listOf(touched), manifest).changedFiles)
    }

    @Test
    fun sourcesNeverNestAndFoldInnerFolders() {
        val config = BackupConfig().withSource("/s/Download").withSource("/s/Download/info/")
        assertEquals(listOf("/s/Download"), config.sources)
        val widened = BackupConfig().withSource("/s/Download/info").withSource("/s/Download")
        assertEquals(listOf("/s/Download"), widened.sources)
        assertFalse(BackupConfig(enabled = true, destinationDeviceId = "d").isRunnable)
        assertTrue(BackupConfig(enabled = true, destinationDeviceId = "d", sources = listOf("/s")).isRunnable)
    }

    @Test
    fun configSurvivesEncodeDecodeAndBadInput() {
        val config = BackupConfig(true, "dev", listOf("/s/a"), 24, false)
        assertEquals(config, BackupConfig.decode(config.encode()))
        assertEquals(BackupConfig(), BackupConfig.decode("not json"))
    }

    @Test
    fun defaultConfigIsDisabledAndRevealsSectionsProgressively() {
        val off = BackupConfig()
        assertFalse(off.enabled)
        assertEquals(
            BackupPageVisibility(
                showDestination = false,
                shouldAutoExpandDestination = false,
                showFolders = false,
                showFrequencyAndOptions = false
            ),
            BackupPageVisibility.from(off)
        )

        val onNoDest = off.copy(enabled = true)
        assertEquals(
            BackupPageVisibility(
                showDestination = true,
                shouldAutoExpandDestination = true,
                showFolders = false,
                showFrequencyAndOptions = false
            ),
            BackupPageVisibility.from(onNoDest)
        )

        val withDestNoFolders = onNoDest.copy(destinationDeviceId = "mac-1")
        assertEquals(
            BackupPageVisibility(
                showDestination = true,
                shouldAutoExpandDestination = false,
                showFolders = true,
                showFrequencyAndOptions = false
            ),
            BackupPageVisibility.from(withDestNoFolders)
        )

        val withFolders = withDestNoFolders.withSource("/storage/emulated/0/DCIM/Camera")
        assertEquals(
            BackupPageVisibility(
                showDestination = true,
                shouldAutoExpandDestination = false,
                showFolders = true,
                showFrequencyAndOptions = true
            ),
            BackupPageVisibility.from(withFolders)
        )
    }
}
