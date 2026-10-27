package com.fileapex.network

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Browse PIN travels only in the X-FileApex-Pin header; no server or daemon code reads or writes it as a URL query. */
class NoPinInUrlGuardTest {
    private fun root(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "shared/src").isDirectory) dir = dir.parentFile
        return requireNotNull(dir) { "project root not found" }
    }

    @Test fun theServerReadsOnlyTheHeader() {
        val server = File(root(), "shared/src/commonMain/kotlin/com/fileapex/network/FileApexServer.kt").readText()
        assertFalse(server.contains("queryParameters[\"pin\"]"))
        assertTrue(server.contains("headers[PeerPinHeader.NAME]"))
    }

    @Test fun theDaemonNeverPutsThePinInAQuery() {
        val go = File(root(), "native/tsnet/daemon").listFiles { f -> f.name.endsWith(".go") && !f.name.endsWith("_test.go") }!!
        go.forEach { assertFalse("${it.name} sets a pin query", it.readText().contains("q.Set(\"pin\"")) }
    }
}
