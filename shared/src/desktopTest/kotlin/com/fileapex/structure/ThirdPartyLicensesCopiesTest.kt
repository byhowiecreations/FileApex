package com.fileapex.structure

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The licence notices ship inside the app, so the packaged copies must match the file in the repository root. */
class ThirdPartyLicensesCopiesTest {
    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "THIRD_PARTY_LICENSES.md").isFile }

    @Test
    fun theBundledCopiesMatchTheRepositoryFile() {
        val rootText = File(root(), "THIRD_PARTY_LICENSES.md").readText()
        listOf("shared/src/desktopMain/resources/legal", "shared/src/androidMain/assets/legal").forEach {
            assertEquals(it, rootText, File(root(), "$it/THIRD_PARTY_LICENSES.md").readText())
        }
    }

    @Test
    fun theTailscaleAndGoNoticesAreReproducedInFull() {
        val text = File(root(), "THIRD_PARTY_LICENSES.md").readText()
        listOf(
            "Copyright (c) 2020 Tailscale Inc & contributors.",
            "Copyright 2009 The Go Authors.",
            "Redistributions in binary form must reproduce the above copyright notice",
            "Neither the name of the copyright holder nor the names of its"
        ).forEach { assertTrue(it, text.contains(it)) }
    }
}
