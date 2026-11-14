package com.fileapex.widget

import android.content.Context
import android.content.Intent
import com.fileapex.domain.widget.WidgetActionTarget
import com.fileapex.domain.widget.WidgetTargetBinding
import com.fileapex.domain.widget.WidgetTargetBindingCodec
import com.fileapex.platform.DirectShareShortcutCoordinator

object WidgetDeepLinks {
    const val ACTION_QUICK_POST = "com.fileapex.action.WIDGET_QUICK_POST"
    const val ACTION_PICK_FILE = "com.fileapex.action.WIDGET_PICK_FILE"
    const val ACTION_PICK_DEVICE = "com.fileapex.action.WIDGET_PICK_DEVICE"
    const val ACTION_BATTERY_POPUP = "com.fileapex.action.WIDGET_BATTERY_POPUP"

    const val EXTRA_WIDGET_ROUTE = "com.fileapex.extra.WIDGET_ROUTE"
    const val EXTRA_ACTION_WIRE_ID = "com.fileapex.extra.WIDGET_ACTION"
    const val EXTRA_BINDING_WIRE = "com.fileapex.extra.WIDGET_BINDING"
    const val EXTRA_PICK_MODE = "com.fileapex.extra.WIDGET_PICK_MODE"
    const val EXTRA_PREFERRED_DEVICE_ID = "com.fileapex.extra.WIDGET_PREFERRED_DEVICE_ID"
    const val EXTRA_APP_WIDGET_ID = "com.fileapex.extra.WIDGET_APP_WIDGET_ID"

    const val ROUTE_BULLETIN = "bulletin"
    const val ROUTE_BROWSE_DEVICE = "browse_device"
    const val ROUTE_FRIENDS = "friends"

    const val PICK_MODE_SEND = "send"
    const val PICK_MODE_GUEST_LINK = "guest_link"

    sealed interface MainDestination {
        data object BulletinBoard : MainDestination
        data class BrowseDevice(val deviceId: String) : MainDestination
        data object Friends : MainDestination
    }

    fun parseMainDestination(intent: Intent?): MainDestination? {
        if (intent == null) return null
        return when (intent.getStringExtra(EXTRA_WIDGET_ROUTE)) {
            ROUTE_BULLETIN -> MainDestination.BulletinBoard
            ROUTE_FRIENDS -> MainDestination.Friends
            ROUTE_BROWSE_DEVICE -> {
                val id = intent.getStringExtra(DirectShareShortcutCoordinator.EXTRA_TARGET_DEVICE_ID)
                    ?.trim().orEmpty()
                if (id.isNotEmpty()) MainDestination.BrowseDevice(id) else null
            }
            else -> null
        }
    }

    fun quickPostIntent(context: Context): Intent =
        Intent(ACTION_QUICK_POST).setPackage(context.packageName).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

    fun pickFileForSendIntent(context: Context, binding: WidgetTargetBinding): Intent =
        Intent(ACTION_PICK_FILE).setPackage(context.packageName).apply {
            putExtra(EXTRA_PICK_MODE, PICK_MODE_SEND)
            putExtra(EXTRA_BINDING_WIRE, WidgetTargetBindingCodec.encode(binding))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

    fun pickFileForGuestLinkIntent(context: Context): Intent =
        Intent(ACTION_PICK_FILE).setPackage(context.packageName).apply {
            putExtra(EXTRA_PICK_MODE, PICK_MODE_GUEST_LINK)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

    fun pickDeviceForActionIntent(context: Context, target: WidgetActionTarget): Intent =
        Intent(ACTION_PICK_DEVICE).setPackage(context.packageName).apply {
            putExtra(EXTRA_ACTION_WIRE_ID, target.wireId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

    fun batteryPopupIntent(context: Context, preferredDeviceId: String? = null): Intent =
        Intent(ACTION_BATTERY_POPUP).setPackage(context.packageName).apply {
            if (!preferredDeviceId.isNullOrBlank()) {
                putExtra(EXTRA_PREFERRED_DEVICE_ID, preferredDeviceId)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
}
