package com.fileapex.domain.launch

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopLaunchInboxTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun filesKeepUnicodeMissingPathsFoldersAndEmptyFilesForSendValidation() {
        val empty = temp.newFile("报告 empty.txt")
        val folder = temp.newFolder("folder")
        val inbox = DesktopLaunchInbox()
        val request = DesktopLaunchRequest(args = listOf(empty.name, "missing.txt", folder.toURI().toString()), cwd = temp.root.path)
        assertTrue(inbox.offer(request))
        val files = inbox.pending.value.single().share!!.files
        assertEquals(listOf(empty.path, File(temp.root, "missing.txt").path, folder.path), files.map { it.absolutePath })
        assertTrue(files.all { it.sizeBytes == 0L })
    }

    @Test fun invitesAndOAuthUrisStaySeparateFromFilesAndRetryIsIdempotent() {
        val inbox = DesktopLaunchInbox()
        val request = DesktopLaunchRequest(args = listOf("fileapex://friend?v=1", "apex://oauth-callback?code=test&state=test"), cwd = temp.root.path)
        assertTrue(inbox.offer(request))
        assertTrue(inbox.offer(request))
        val input = inbox.pending.value.single()
        assertNull(input.share)
        assertEquals(request.args, input.uris)
        inbox.consume(input.id)
        assertTrue(inbox.offer(request))
        assertTrue(inbox.pending.value.isEmpty())
    }

    @Test fun aFullInboxRefusesWithoutOverwritingAnEarlierRequest() {
        val inbox = DesktopLaunchInbox()
        repeat(32) { assertTrue(inbox.offer(DesktopLaunchRequest(cwd = temp.root.path))) }
        val overflow = DesktopLaunchRequest(cwd = temp.root.path)
        assertFalse(inbox.offer(overflow))
        inbox.consume(inbox.pending.value.first().id)
        assertTrue(inbox.offer(overflow))
        assertEquals(32, inbox.pending.value.size)
    }
}
