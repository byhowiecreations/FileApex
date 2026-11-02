package com.fileapex.friends.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The queue lives inside the Groups panel; no floating pill may cover the top of the Devices page. */
class FriendsQueuePillTest {
    private val source = File("src/commonMain/kotlin/com/fileapex/friends/ui/FriendTransfersPanel.kt").readText()

    @Test fun noFloatingPillIsShownOverTheDevicesPage() {
        assertFalse("a Popup would float over the Devices page", "Popup(" in source)
        assertFalse(source.contains("friends_queue_count") || source.contains("friends_queue_recent"))
    }

    @Test fun theQueueStillOpensFromTheGroupsPanelButton() {
        assertTrue("if (expanded)" in source)
        assertTrue("FriendsQueueUi.expanded.value = true" in source)
    }
}
