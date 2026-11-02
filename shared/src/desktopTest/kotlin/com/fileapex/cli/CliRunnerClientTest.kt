package com.fileapex.cli

import com.fileapex.di.FileApexServices
import org.junit.Assert.*
import org.junit.Test

class CliRunnerClientTest {
    @Test fun aCommandUsesTheAppAndNeverBootstrapsAnotherDatabase() {
        val ready = FileApexServices.isDatabaseReady()
        var received: List<String>? = null
        val result = CliRunner.runHeadless(listOf("devices"), "/tmp") { args, cwd ->
            received = args
            assertEquals("/tmp", cwd)
            37
        }
        assertEquals(37, result)
        assertEquals(listOf("devices"), received)
        assertEquals(ready, FileApexServices.isDatabaseReady())
    }

    @Test fun anUnavailableAppDoesNotTriggerStandaloneBootstrap() {
        val ready = FileApexServices.isDatabaseReady()
        assertEquals(1, CliRunner.runHeadless(listOf("devices"), "/tmp") { _, _ -> null })
        assertEquals(ready, FileApexServices.isDatabaseReady())
    }
}
