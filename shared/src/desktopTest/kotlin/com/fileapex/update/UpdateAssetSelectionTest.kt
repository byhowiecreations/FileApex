package com.fileapex.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateAssetSelectionTest {
    private fun asset(name: String) = GitHubReleaseAsset(name, "https://example.invalid/$name", 10L)

    private val release = listOf(
        asset("FileApex-v0.16.3b.apk"),
        asset("FileApex-v0.16.3b-play.apk"),
        asset("FileApex-v0.16.3b-Intel.dmg"),
        asset("FileApex-v0.16.3b-Silicon.dmg"),
        asset("FileApex-v0.16.3b.exe"),
    )

    @Test
    fun macPicksTheDmgForItsOwnChip() {
        assertEquals("FileApex-v0.16.3b-Silicon.dmg", macAssetFor(release, wantsSilicon = true)?.name)
        assertEquals("FileApex-v0.16.3b-Intel.dmg", macAssetFor(release, wantsSilicon = false)?.name)
    }

    @Test
    fun macNeverFallsBackToTheOtherChipsDmg() {
        val siliconOnly = release.filterNot { it.name.contains("Intel") }
        assertNull(macAssetFor(siliconOnly, wantsSilicon = false))
        assertTrue(macAssetFor(siliconOnly, wantsSilicon = true) != null)
    }

    @Test
    fun runningBuildAheadOfTheReleasePageIsNotAnUpdate() {
        assertTrue(isInstalledVersionStrictlyNewer("0.16.3b", "v0.16.3a"))
        assertTrue(!isRemoteVersionNewer("0.16.3b", "v0.16.3a"))
        assertTrue(!isInstalledVersionStrictlyNewer("0.16.3b", "v0.16.3b"))
    }
}
