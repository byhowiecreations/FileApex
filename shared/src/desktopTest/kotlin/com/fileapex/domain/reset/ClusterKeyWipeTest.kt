package com.fileapex.domain.reset

import com.fileapex.cloud.seal.ClusterRecordKey
import com.fileapex.cloud.seal.ClusterRecordKeys
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ClusterKeyWipeTest {
    private var stored = ""

    @Before
    fun setUp() {
        stored = ClusterRecordKey.generate().toJson()
        ClusterRecordKeys.read = { stored }
        ClusterRecordKeys.write = { stored = it }
        ClusterRecordKeys.forgetForTests()
    }

    @After
    fun tearDown() = ClusterRecordKeys.forgetForTests()

    @Test
    fun leavingTheClusterDropsTheCachedKeyAsWellAsTheStoredOne() {
        assertNotNull(ClusterRecordKeys.current())
        stored = ClusterRecordKey.generate().toJson()
        ClusterResetManager.forgetHeldKeys()
        stored = ""
        assertNull(ClusterRecordKeys.current())
        assertEquals("", stored)
    }
}
