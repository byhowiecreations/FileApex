package com.fileapex.widget

import android.content.Intent
import com.fileapex.domain.widget.WidgetActionTarget
import com.fileapex.domain.widget.WidgetTargetBinding
import com.fileapex.domain.widget.WidgetTargetBindingCodec
import com.fileapex.platform.DirectShareShortcutCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetDeepLinksTest {

    @Test
    fun parsesBulletinBrowseDeviceAndFriendsDestinations() {
        val bulletin = Intent().putExtra(WidgetDeepLinks.EXTRA_WIDGET_ROUTE, WidgetDeepLinks.ROUTE_BULLETIN)
        assertEquals(WidgetDeepLinks.MainDestination.BulletinBoard, WidgetDeepLinks.parseMainDestination(bulletin))

        val friends = Intent().putExtra(WidgetDeepLinks.EXTRA_WIDGET_ROUTE, WidgetDeepLinks.ROUTE_FRIENDS)
        assertEquals(WidgetDeepLinks.MainDestination.Friends, WidgetDeepLinks.parseMainDestination(friends))

        val browse = Intent()
            .putExtra(WidgetDeepLinks.EXTRA_WIDGET_ROUTE, WidgetDeepLinks.ROUTE_BROWSE_DEVICE)
            .putExtra(DirectShareShortcutCoordinator.EXTRA_TARGET_DEVICE_ID, "fx-peer-99")
        assertEquals(WidgetDeepLinks.MainDestination.BrowseDevice("fx-peer-99"), WidgetDeepLinks.parseMainDestination(browse))

        val emptyBrowse = Intent().putExtra(WidgetDeepLinks.EXTRA_WIDGET_ROUTE, WidgetDeepLinks.ROUTE_BROWSE_DEVICE)
        assertNull(WidgetDeepLinks.parseMainDestination(emptyBrowse))
        assertNull(WidgetDeepLinks.parseMainDestination(Intent()))
        assertNull(WidgetDeepLinks.parseMainDestination(null))
    }

    @Test
    fun buildsExplicitPackageScopedPickerAndQuickPostIntents() {
        val ctx = RuntimeEnvironment.getApplication()
        val quickPost = WidgetDeepLinks.quickPostIntent(ctx)
        assertEquals(WidgetDeepLinks.ACTION_QUICK_POST, quickPost.action)
        assertEquals(ctx.packageName, quickPost.`package`)

        val sendPicker = WidgetDeepLinks.pickFileForSendIntent(ctx, WidgetTargetBinding.SpecificDevice("fx-peer-1"))
        assertEquals(WidgetDeepLinks.ACTION_PICK_FILE, sendPicker.action)
        assertEquals(WidgetDeepLinks.PICK_MODE_SEND, sendPicker.getStringExtra(WidgetDeepLinks.EXTRA_PICK_MODE))
        assertEquals(
            WidgetTargetBinding.SpecificDevice("fx-peer-1"),
            WidgetTargetBindingCodec.decode(sendPicker.getStringExtra(WidgetDeepLinks.EXTRA_BINDING_WIRE))
        )

        val linkPicker = WidgetDeepLinks.pickFileForGuestLinkIntent(ctx)
        assertEquals(WidgetDeepLinks.PICK_MODE_GUEST_LINK, linkPicker.getStringExtra(WidgetDeepLinks.EXTRA_PICK_MODE))

        val devicePicker = WidgetDeepLinks.pickDeviceForActionIntent(ctx, WidgetActionTarget.RING_PHONE)
        assertEquals(WidgetDeepLinks.ACTION_PICK_DEVICE, devicePicker.action)
        assertEquals(WidgetActionTarget.RING_PHONE.wireId, devicePicker.getStringExtra(WidgetDeepLinks.EXTRA_ACTION_WIRE_ID))
    }
}
