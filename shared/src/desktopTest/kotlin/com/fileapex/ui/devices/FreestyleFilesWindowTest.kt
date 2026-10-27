package com.fileapex.ui.devices

import com.fileapex.ui.FRIEND_ROW_PREFIX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The freestyle file window on a narrow phone, and a friend send which is not a remote folder. */
class FreestyleFilesWindowTest {

    @Test
    fun aFriendSendDoesNotOpenARemoteFolder() {
        assertFalse(browsesRemoteFolder(FRIEND_ROW_PREFIX + "rome"))
        assertTrue(browsesRemoteFolder("paired-device"))
    }

    @Test
    fun aFriendRowIdGivesTheFriendKeyAndAPairedDeviceGivesNone() {
        assertEquals("rome", friendKeyOf(FRIEND_ROW_PREFIX + "rome"))
        assertEquals(null, friendKeyOf("paired-device"))
        assertEquals(null, friendKeyOf(FRIEND_ROW_PREFIX))
    }

    @Test
    fun draggingAWindowWiderThanThePhoneStaysPut() {
        // Honor cover: 1256px wide, 16dp at 560dpi is 56px, a 400dp window is 1400px.
        val edge = 56f
        val farEdge = 1256f - 56f - 1400f
        assertEquals(-200f, farEdge, 0.01f)
        assertEquals(edge, clampPanelOffset(80f, edge, farEdge), 0.01f)
        assertEquals(edge, clampPanelOffset(10f, edge, farEdge), 0.01f)
    }

    @Test
    fun aWindowThatFitsSlidesBetweenTheMargins() {
        assertEquals(56f, clampPanelOffset(10f, 56f, 400f), 0.01f)
        assertEquals(200f, clampPanelOffset(200f, 56f, 400f), 0.01f)
        assertEquals(400f, clampPanelOffset(900f, 56f, 400f), 0.01f)
    }

    @Test
    fun aRequestedWindowShrinksToTheRoomOnScreen() {
        val room = 1256f - 56f * 2f
        val min = 320f * 3.5f
        assertEquals(room, fittedPanelPx(1400f, room, min), 0.01f)
        assertEquals(min, fittedPanelPx(100f, room, min), 0.01f)
    }
}
