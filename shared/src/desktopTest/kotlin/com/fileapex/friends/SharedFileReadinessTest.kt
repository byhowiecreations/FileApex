package com.fileapex.friends

import com.fileapex.domain.share.IncomingShareFile
import com.fileapex.domain.share.IncomingSharePayload
import com.fileapex.domain.share.hasResolvedPaths
import org.junit.Assert.*
import org.junit.Test

class SharedFileReadinessTest {
    @Test fun anEmptyFileWithAResolvedPathCanReachSendValidation() {
        assertTrue(IncomingSharePayload("empty-file", listOf(IncomingShareFile("empty.txt", "/tmp/example/empty.txt", 0))).hasResolvedPaths)
    }

    @Test fun aMissingResolvedPathCanReportItsFailureInsteadOfWaitingForStagingForever() {
        assertTrue(IncomingSharePayload("missing", listOf(IncomingShareFile("missing.txt", "/tmp/example/missing.txt", 0))).hasResolvedPaths)
    }

    @Test fun pendingOrMalformedStagingStillCannotBeSent() {
        assertFalse(IncomingSharePayload("none", emptyList()).hasResolvedPaths)
        assertFalse(IncomingSharePayload("pending", listOf(IncomingShareFile("pending.txt", "", 0))).hasResolvedPaths)
        assertFalse(IncomingSharePayload("bad", listOf(IncomingShareFile("bad.txt", "/tmp/example/bad.txt", -1))).hasResolvedPaths)
    }
}
