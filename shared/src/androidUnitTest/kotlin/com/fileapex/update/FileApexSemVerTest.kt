package com.fileapex.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileApexSemVerTest {

    @Test
    fun olderGithubTagsAreNotNewerThanCurrent() {
        assertFalse(isRemoteVersionNewer("0.9.2c", "v0.6.26"))
        assertFalse(isRemoteVersionNewer("0.9.2c", "v0.8.3"))
        assertFalse(isRemoteVersionNewer("0.9.2b", "v0.6.26"))
    }

    @Test
    fun newerPatchAndLetterAreNewer() {
        assertTrue(isRemoteVersionNewer("0.8.3", "v0.9.2c"))
        assertTrue(isRemoteVersionNewer("0.9.2b", "0.9.2c"))
        assertTrue(isRemoteVersionNewer("0.9.2", "0.9.2a"))
    }

    @Test
    fun sameVersionIsNotNewer() {
        assertFalse(isRemoteVersionNewer("0.9.2c", "v0.9.2c"))
        assertFalse(isRemoteVersionNewer("0.8.3", "v0.8.3"))
    }
}
