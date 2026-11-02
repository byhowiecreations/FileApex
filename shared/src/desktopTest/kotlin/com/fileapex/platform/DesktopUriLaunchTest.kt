package com.fileapex.platform

import com.fileapex.domain.launch.DesktopLaunchInbox
import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.*
import org.junit.Test

class DesktopUriLaunchTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun nativeInviteAndOAuthEventsWaitForTheExistingAppToRouteThem() {
        val inbox = DesktopLaunchInbox()
        val invite = URI("fileapex://friend?v=1")
        val oauth = URI("apex://oauth-callback?code=test&state=test")
        assertTrue(DesktopUriHandler.queueUri(invite, inbox))
        assertTrue(DesktopUriHandler.queueUri(oauth, inbox))
        assertEquals(listOf(listOf(invite.toString()), listOf(oauth.toString())), inbox.pending.value.map { it.uris })
        assertTrue(inbox.pending.value.all { it.share == null })
    }

    @Test fun nativeFileEventsEnterTheSameShareQueue() = runBlocking {
        val inbox = DesktopLaunchInbox()
        val file = temp.newFile("文件.txt")
        withTimeout(5_000L) { DesktopUriHandler.queueFiles(listOf(file), inbox).join() }
        assertEquals(file.path, inbox.pending.value.single().share!!.files.single().absolutePath)
    }

    @Test fun stoppingTheHandlerCancelsPendingFileEventsAndItCanStartAgain() = runBlocking {
        val inbox = DesktopLaunchInbox()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val slow = object : java.io.File(temp.newFile("late.txt").path) {
            override fun getAbsolutePath(): String { entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS); return super.getAbsolutePath() }
        }
        val job = DesktopUriHandler.queueFiles(listOf(slow), inbox)
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        DesktopUriHandler.stop()
        release.countDown()
        withTimeout(5_000L) { job.join() }
        assertTrue(job.isCancelled)
        assertTrue(inbox.pending.value.isEmpty())
        val again = temp.newFile("again.txt")
        withTimeout(5_000L) { DesktopUriHandler.queueFiles(listOf(again), inbox).join() }
        assertEquals(1, inbox.pending.value.size)
    }
}
