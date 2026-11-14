package com.fileapex.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.fileapex.R
import com.fileapex.di.FileApexServices
import com.fileapex.domain.widget.WidgetActionTarget
import com.fileapex.domain.widget.WidgetBatteryFormatter
import com.fileapex.domain.widget.WidgetConfig
import com.fileapex.domain.widget.WidgetKind
import com.fileapex.domain.widget.WidgetTargetBinding
import com.fileapex.domain.widget.WidgetTargetBindingCodec
import com.fileapex.i18n.AppI18n
import com.fileapex.platform.FileApexAndroidBootstrap
import kotlinx.coroutines.runBlocking
import java.time.ZoneId

object WidgetUiFormatter {

    fun updateAllWidgets(context: Context) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext) ?: return
        allProviders().forEach { (kind, cls) ->
            val ids = manager.getAppWidgetIds(ComponentName(appContext, cls))
            ids.forEach { id -> renderWidget(appContext, manager, id, kind) }
        }
    }

    fun updateBulletinWidgets(context: Context) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(appContext, BulletinBoardWidgetProvider::class.java))
        if (ids.isEmpty()) return
        ids.forEach { id -> renderWidget(appContext, manager, id, WidgetKind.BULLETIN_BOARD) }
        @Suppress("DEPRECATION")
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_bulletin_list)
    }

    fun updateWidgetById(context: Context, appWidgetId: Int) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext) ?: return
        val kind = resolveKindForWidget(appContext, manager, appWidgetId) ?: return
        renderWidget(appContext, manager, appWidgetId, kind)
        if (kind == WidgetKind.BULLETIN_BOARD) {
            @Suppress("DEPRECATION")
            manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_bulletin_list)
        }
    }

    fun resolveKindForWidget(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int
    ): WidgetKind? {
        val providerClass = manager.getAppWidgetInfo(appWidgetId)?.provider?.className
        allProviders().firstOrNull { it.second.name == providerClass }?.let { return it.first }
        return WidgetConfigStore(context).load(appWidgetId)?.kind
    }

    private fun renderWidget(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        kind: WidgetKind
    ) {
        val ready = FileApexAndroidBootstrap.ensureInitialized(context)
        val store = WidgetConfigStore(context)
        val configured = store.hasConfig(appWidgetId)
        val config = store.load(appWidgetId) ?: WidgetConfig(appWidgetId = appWidgetId, kind = kind)
        val options = manager.getAppWidgetOptions(appWidgetId) ?: Bundle.EMPTY
        val views = when (kind) {
            WidgetKind.QUICK_ACTIONS -> renderQuickActions(context, appWidgetId, config, configured, options, ready)
            WidgetKind.DEVICE_CARD_2X2 -> renderDeviceCard(context, appWidgetId, config, configured, store, ready)
            WidgetKind.DROP_TARGET -> renderDropTarget(context, appWidgetId, config, configured, ready)
            WidgetKind.BULLETIN_BOARD -> renderBulletinBoard(context, appWidgetId, ready)
        }
        manager.updateAppWidget(appWidgetId, views)
    }

    private fun renderQuickActions(
        context: Context,
        appWidgetId: Int,
        config: WidgetConfig,
        configured: Boolean,
        options: Bundle,
        ready: Boolean
    ): RemoteViews {
        if (!configured) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_actions_1x1)
            views.setImageViewResource(R.id.widget_action_icon_0, R.drawable.ic_widget_settings)
            views.setTextViewText(R.id.widget_action_label_0, text(ready, "widget_tap_to_configure", "Tap to configure"))
            views.setViewVisibility(R.id.widget_binding_badge, View.GONE)
            views.setOnClickPendingIntent(R.id.widget_action_slot_0, configurePendingIntent(context, appWidgetId))
            return views
        }
        val actions = config.normalizedActions()
        val minWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val layoutId = when {
            actions.size <= 1 -> R.layout.widget_quick_actions_1x1
            actions.size == 2 -> R.layout.widget_quick_actions_2x1
            minHeightDp >= 95 && minWidthDp in 1..210 -> R.layout.widget_quick_actions_2x2
            else -> R.layout.widget_quick_actions_4x1
        }
        val views = RemoteViews(context.packageName, layoutId)
        views.setTextViewText(R.id.widget_binding_badge, bindingLabel(context, config.binding, ready))
        if (layoutId == R.layout.widget_quick_actions_1x1) {
            views.setViewVisibility(R.id.widget_binding_badge, View.VISIBLE)
        } else {
            val showHeader = minHeightDp == 0 || minHeightDp >= 80
            views.setViewVisibility(R.id.widget_quick_header, if (showHeader) View.VISIBLE else View.GONE)
            views.setOnClickPendingIntent(R.id.widget_configure_btn, configurePendingIntent(context, appWidgetId))
        }
        val slotIds = listOf(
            Triple(R.id.widget_action_slot_0, R.id.widget_action_icon_0, R.id.widget_action_label_0),
            Triple(R.id.widget_action_slot_1, R.id.widget_action_icon_1, R.id.widget_action_label_1),
            Triple(R.id.widget_action_slot_2, R.id.widget_action_icon_2, R.id.widget_action_label_2),
            Triple(R.id.widget_action_slot_3, R.id.widget_action_icon_3, R.id.widget_action_label_3)
        )
        val maxSlots = when (layoutId) {
            R.layout.widget_quick_actions_1x1 -> 1
            R.layout.widget_quick_actions_2x1 -> 2
            else -> 4
        }
        for (index in 0 until maxSlots) {
            val (containerId, iconId, labelId) = slotIds[index]
            val target = actions.getOrNull(index)
            if (target == null) {
                views.setViewVisibility(containerId, View.GONE)
                continue
            }
            views.setViewVisibility(containerId, View.VISIBLE)
            views.setImageViewResource(iconId, iconForAction(target))
            views.setTextViewText(labelId, actionLabel(context, target, ready))
            views.setOnClickPendingIntent(
                containerId,
                actionPendingIntent(
                    context = context,
                    appWidgetId = appWidgetId,
                    requestCode = appWidgetId * 10 + index,
                    target = target,
                    binding = config.binding
                )
            )
        }
        return views
    }

    private fun renderDeviceCard(
        context: Context,
        appWidgetId: Int,
        config: WidgetConfig,
        configured: Boolean,
        store: WidgetConfigStore,
        ready: Boolean
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_device_card)
        val specific = if (configured) config.binding as? WidgetTargetBinding.SpecificDevice else null
        val deviceId = specific?.deviceId
        val deviceName = if (deviceId != null) {
            resolveDeviceName(deviceId, ready)
        } else {
            text(ready, "widget_tap_to_configure", "Tap to configure")
        }
        val isOnline = ready && deviceId != null &&
            FileApexServices.presenceMonitor.onlineDeviceIds.value.contains(deviceId)

        views.setTextViewText(R.id.widget_device_name, deviceName)
        views.setImageViewResource(
            R.id.widget_device_dot,
            if (isOnline) R.drawable.widget_dot_online else R.drawable.widget_dot_offline
        )
        val statusText = when {
            !configured || deviceId == null -> text(ready, "widget_tap_to_configure", "Tap to configure")
            isOnline -> text(ready, "widget_device_online", "Online")
            else -> text(ready, "widget_device_offline", "Offline")
        }
        views.setTextViewText(R.id.widget_device_status, statusText)

        val reading = deviceId?.let { store.loadBatteryReading(it) }
        val batteryText = WidgetBatteryFormatter.formatPillText(reading, ZoneId.systemDefault().id)
            ?: text(ready, "widget_battery_placeholder", "Tap refresh for battery")
        views.setTextViewText(R.id.widget_device_battery_text, batteryText)
        views.setTextViewText(R.id.widget_device_browse_label, text(ready, "widget_action_browse_device", "Browse device"))

        views.setOnClickPendingIntent(R.id.widget_configure_btn, configurePendingIntent(context, appWidgetId))
        val browseIntent = if (configured && deviceId != null) {
            actionPendingIntent(context, appWidgetId, appWidgetId * 10 + 1, WidgetActionTarget.BROWSE_DEVICE, config.binding)
        } else {
            configurePendingIntent(context, appWidgetId)
        }
        val batteryIntent = if (configured) {
            actionPendingIntent(context, appWidgetId, appWidgetId * 10 + 2, WidgetActionTarget.CHECK_BATTERY, config.binding)
        } else {
            configurePendingIntent(context, appWidgetId)
        }
        views.setOnClickPendingIntent(R.id.widget_device_header, browseIntent)
        views.setOnClickPendingIntent(R.id.widget_device_browse_btn, browseIntent)
        views.setOnClickPendingIntent(R.id.widget_device_battery_pill, batteryIntent)
        return views
    }

    private fun renderDropTarget(
        context: Context,
        appWidgetId: Int,
        config: WidgetConfig,
        configured: Boolean,
        ready: Boolean
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_drop_target)
        if (!configured) {
            views.setTextViewText(R.id.widget_drop_title, text(ready, "widget_tap_to_configure", "Tap to configure"))
            views.setViewVisibility(R.id.widget_binding_badge, View.GONE)
            views.setOnClickPendingIntent(R.id.widget_drop_tap_area, configurePendingIntent(context, appWidgetId))
        } else {
            views.setTextViewText(R.id.widget_drop_title, text(ready, "widget_drop_title", "Drop files"))
            views.setViewVisibility(R.id.widget_binding_badge, View.VISIBLE)
            views.setTextViewText(R.id.widget_binding_badge, bindingLabel(context, config.binding, ready))
            views.setOnClickPendingIntent(
                R.id.widget_drop_tap_area,
                actionPendingIntent(context, appWidgetId, appWidgetId * 10, WidgetActionTarget.SEND_FILE, config.binding)
            )
        }
        return views
    }

    private fun renderBulletinBoard(
        context: Context,
        appWidgetId: Int,
        ready: Boolean
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_bulletin_board)
        views.setTextViewText(R.id.widget_bulletin_title, text(ready, "widget_action_open_bulletin", "Bulletin Board"))
        views.setTextViewText(R.id.widget_bulletin_quick_post_btn, "+ " + text(ready, "widget_quick_post_send", "Post"))
        views.setTextViewText(R.id.widget_bulletin_empty, text(ready, "widget_bulletin_empty", "No Bulletin Board notes yet"))

        val serviceIntent = Intent(context, BulletinWidgetRemoteViewsService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse("fileapex://widget/bulletin/$appWidgetId")
        }
        @Suppress("DEPRECATION")
        views.setRemoteAdapter(R.id.widget_bulletin_list, serviceIntent)
        views.setEmptyView(R.id.widget_bulletin_list, R.id.widget_bulletin_empty)

        views.setOnClickPendingIntent(
            R.id.widget_bulletin_header_open,
            actionPendingIntent(context, appWidgetId, appWidgetId * 10 + 1, WidgetActionTarget.OPEN_BULLETIN, WidgetTargetBinding.AskEachTime)
        )
        views.setOnClickPendingIntent(
            R.id.widget_bulletin_quick_post_btn,
            actionPendingIntent(context, appWidgetId, appWidgetId * 10 + 2, WidgetActionTarget.POST_TO_BOARD, WidgetTargetBinding.AskEachTime)
        )

        val itemTemplateIntent = Intent(context, WidgetActionReceiver::class.java).apply {
            action = WidgetActionReceiver.ACTION_LIST_ITEM_CLICK
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        views.setPendingIntentTemplate(
            R.id.widget_bulletin_list,
            PendingIntent.getBroadcast(context, appWidgetId * 10 + 3, itemTemplateIntent, flags)
        )
        return views
    }

    fun actionLabel(context: Context, target: WidgetActionTarget, ready: Boolean = FileApexAndroidBootstrap.ensureInitialized(context)): String =
        if (ready) AppI18n.t(target.labelKey) else target.wireId.replace('_', ' ')

    fun iconForAction(target: WidgetActionTarget): Int = when (target) {
        WidgetActionTarget.SEND_LAST_PHOTO -> R.drawable.ic_widget_photo
        WidgetActionTarget.SEND_LAST_SCREENSHOT -> R.drawable.ic_widget_screenshot
        WidgetActionTarget.SEND_LAST_VIDEO -> R.drawable.ic_widget_video
        WidgetActionTarget.SEND_LAST_DOWNLOAD -> R.drawable.ic_widget_download
        WidgetActionTarget.SEND_FILE -> R.drawable.ic_widget_send_file
        WidgetActionTarget.SEND_CLIPBOARD -> R.drawable.ic_widget_clipboard_send
        WidgetActionTarget.PULL_CLIPBOARD -> R.drawable.ic_widget_clipboard_pull
        WidgetActionTarget.POST_TO_BOARD -> R.drawable.ic_widget_post
        WidgetActionTarget.OPEN_BULLETIN -> R.drawable.ic_widget_bulletin
        WidgetActionTarget.BROWSE_DEVICE -> R.drawable.ic_widget_browse
        WidgetActionTarget.CHECK_BATTERY -> R.drawable.ic_widget_battery
        WidgetActionTarget.RING_PHONE -> R.drawable.ic_widget_ring
        WidgetActionTarget.SHARE_AS_LINK -> R.drawable.ic_widget_link
        WidgetActionTarget.BACKUP_NOW -> R.drawable.ic_widget_backup
        WidgetActionTarget.TOGGLE_QUEUE_PAUSE -> R.drawable.ic_widget_pause
        WidgetActionTarget.OPEN_FRIENDS -> R.drawable.ic_widget_friends
    }

    private fun bindingLabel(context: Context, binding: WidgetTargetBinding, ready: Boolean): String = when (binding) {
        is WidgetTargetBinding.SpecificDevice -> resolveDeviceName(binding.deviceId, ready)
        WidgetTargetBinding.AllOnline -> text(ready, "widget_target_all_online", "All online devices")
        WidgetTargetBinding.AskEachTime -> text(ready, "widget_target_ask", "Ask each time")
    }

    private fun resolveDeviceName(deviceId: String, ready: Boolean): String {
        if (!ready) return deviceId
        val dev = runBlocking { FileApexServices.deviceRepository.getDevice(deviceId) }
        return dev?.deviceName?.takeIf { it.isNotBlank() } ?: deviceId
    }

    private fun text(ready: Boolean, key: String, fallback: String): String =
        if (ready) AppI18n.t(key) else fallback

    private fun actionPendingIntent(
        context: Context,
        appWidgetId: Int,
        requestCode: Int,
        target: WidgetActionTarget,
        binding: WidgetTargetBinding
    ): PendingIntent {
        val intent = Intent(context, WidgetActionReceiver::class.java).apply {
            action = WidgetActionReceiver.ACTION_RUN_WIDGET_ACTION
            data = Uri.parse("fileapex://widget/action/$requestCode")
            putExtra(WidgetDeepLinks.EXTRA_APP_WIDGET_ID, appWidgetId)
            putExtra(WidgetDeepLinks.EXTRA_ACTION_WIRE_ID, target.wireId)
            putExtra(WidgetDeepLinks.EXTRA_BINDING_WIRE, WidgetTargetBindingCodec.encode(binding))
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun configurePendingIntent(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, WidgetConfigureActivity::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
            data = Uri.parse("fileapex://widget/configure/$appWidgetId")
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            appWidgetId * 10 + 9,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun allProviders(): List<Pair<WidgetKind, Class<*>>> = listOf(
        WidgetKind.QUICK_ACTIONS to QuickActionsWidgetProvider::class.java,
        WidgetKind.DEVICE_CARD_2X2 to DeviceCardWidgetProvider::class.java,
        WidgetKind.DROP_TARGET to DropTargetWidgetProvider::class.java,
        WidgetKind.BULLETIN_BOARD to BulletinBoardWidgetProvider::class.java
    )
}
