package com.fileapex.ui.transfer

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** A moving file is drawn by TransferRow only; the places that used to draw their own bar must not grow one again. */
class TransferUiGuardTest {
    private val base = File("src/commonMain/kotlin/com/fileapex")
    private val formerlySeparate = listOf(
        "ui/LiveTransferBanner.kt", "ui/TransferQueueScreen.kt", "friends/ui/FriendTransferRow.kt", "ui/ShareSendScreen.kt", "ui/explorer/ExplorerDialogs.kt"
    )

    @Test
    fun `the former transfer UIs draw no progress bar of their own`() {
        val offenders = formerlySeparate.filter { File(base, it).readText().contains("LinearProgressIndicator") }
        assertTrue("own progress bars in: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the old separate transfer composables are gone`() {
        assertTrue(!File(base, "ui/TransferProgressBlock.kt").exists())
        assertTrue(!File(base, "ui/TransferQueueScreen.kt").readText().contains("QueuedTransferRow"))
    }
}
