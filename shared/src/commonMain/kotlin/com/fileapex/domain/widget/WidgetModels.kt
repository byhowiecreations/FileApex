package com.fileapex.domain.widget

import com.fileapex.util.TimeUtils

enum class WidgetKind(
    val wireId: String,
    val maxActions: Int,
    val supportsTargetBinding: Boolean
) {
    QUICK_ACTIONS("quick_actions", maxActions = 4, supportsTargetBinding = true),
    DEVICE_CARD_2X2("device_card", maxActions = 0, supportsTargetBinding = true),
    DROP_TARGET("drop_target", maxActions = 0, supportsTargetBinding = true),
    BULLETIN_BOARD("bulletin_board", maxActions = 0, supportsTargetBinding = false);

    companion object {
        fun fromWireId(raw: String?): WidgetKind? = when (raw) {
            "quick_actions", "quick_1x1", "quick_2x1", "quick_4x1" -> QUICK_ACTIONS
            else -> entries.firstOrNull { it.wireId == raw }
        }
    }
}

enum class WidgetActionTarget(
    val wireId: String,
    val labelKey: String,
    val requiresMediaAccess: Boolean,
    val requiresDeviceTarget: Boolean,
    val supportsAllOnline: Boolean
) {
    SEND_LAST_PHOTO("send_last_photo", "widget_action_send_last_photo", requiresMediaAccess = true, requiresDeviceTarget = true, supportsAllOnline = true),
    SEND_LAST_SCREENSHOT("send_last_screenshot", "widget_action_send_last_screenshot", requiresMediaAccess = true, requiresDeviceTarget = true, supportsAllOnline = true),
    SEND_LAST_VIDEO("send_last_video", "widget_action_send_last_video", requiresMediaAccess = true, requiresDeviceTarget = true, supportsAllOnline = true),
    SEND_LAST_DOWNLOAD("send_last_download", "widget_action_send_last_download", requiresMediaAccess = true, requiresDeviceTarget = true, supportsAllOnline = true),
    SEND_FILE("send_file", "widget_action_send_file", requiresMediaAccess = false, requiresDeviceTarget = true, supportsAllOnline = true),
    SEND_CLIPBOARD("send_clipboard", "widget_action_send_clipboard", requiresMediaAccess = false, requiresDeviceTarget = true, supportsAllOnline = true),
    PULL_CLIPBOARD("pull_clipboard", "widget_action_pull_clipboard", requiresMediaAccess = false, requiresDeviceTarget = true, supportsAllOnline = false),
    POST_TO_BOARD("post_to_board", "widget_action_post_to_board", requiresMediaAccess = false, requiresDeviceTarget = false, supportsAllOnline = false),
    OPEN_BULLETIN("open_bulletin", "widget_action_open_bulletin", requiresMediaAccess = false, requiresDeviceTarget = false, supportsAllOnline = false),
    BROWSE_DEVICE("browse_device", "widget_action_browse_device", requiresMediaAccess = false, requiresDeviceTarget = true, supportsAllOnline = false),
    CHECK_BATTERY("check_battery", "widget_action_check_battery", requiresMediaAccess = false, requiresDeviceTarget = true, supportsAllOnline = false),
    RING_PHONE("ring_phone", "widget_action_ring_phone", requiresMediaAccess = false, requiresDeviceTarget = true, supportsAllOnline = false),
    SHARE_AS_LINK("share_as_link", "widget_action_share_as_link", requiresMediaAccess = false, requiresDeviceTarget = false, supportsAllOnline = false),
    BACKUP_NOW("backup_now", "widget_action_backup_now", requiresMediaAccess = false, requiresDeviceTarget = false, supportsAllOnline = false),
    TOGGLE_QUEUE_PAUSE("toggle_queue_pause", "widget_action_toggle_queue_pause", requiresMediaAccess = false, requiresDeviceTarget = false, supportsAllOnline = false),
    OPEN_FRIENDS("open_friends", "widget_action_open_friends", requiresMediaAccess = false, requiresDeviceTarget = false, supportsAllOnline = false)
}

sealed interface WidgetTargetBinding {
    data class SpecificDevice(val deviceId: String) : WidgetTargetBinding
    data object AllOnline : WidgetTargetBinding
    data object AskEachTime : WidgetTargetBinding
}

object WidgetTargetBindingCodec {
    private const val PREFIX_DEVICE = "device:"
    private const val ALL_ONLINE = "all_online"
    private const val ASK = "ask"

    fun encode(binding: WidgetTargetBinding): String = when (binding) {
        is WidgetTargetBinding.SpecificDevice -> PREFIX_DEVICE + binding.deviceId.trim()
        WidgetTargetBinding.AllOnline -> ALL_ONLINE
        WidgetTargetBinding.AskEachTime -> ASK
    }

    fun decode(raw: String?): WidgetTargetBinding {
        val value = raw?.trim().orEmpty()
        return when {
            value == ALL_ONLINE -> WidgetTargetBinding.AllOnline
            value.startsWith(PREFIX_DEVICE) -> {
                val id = value.removePrefix(PREFIX_DEVICE).trim()
                if (id.isNotEmpty()) WidgetTargetBinding.SpecificDevice(id) else WidgetTargetBinding.AskEachTime
            }
            else -> WidgetTargetBinding.AskEachTime
        }
    }
}

data class WidgetConfig(
    val appWidgetId: Int,
    val kind: WidgetKind,
    val binding: WidgetTargetBinding = WidgetTargetBinding.AskEachTime,
    val actions: List<WidgetActionTarget> = emptyList()
) {
    fun normalizedActions(): List<WidgetActionTarget> {
        if (kind.maxActions <= 0) return emptyList()
        val chosen = if (actions.isEmpty()) WidgetActionCatalog.defaultActionsFor(kind) else actions
        return chosen.take(kind.maxActions)
    }
}

object WidgetActionCatalog {
    fun allTargets(): List<WidgetActionTarget> = WidgetActionTarget.entries

    fun byWireId(id: String?): WidgetActionTarget? {
        val trimmed = id?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return WidgetActionTarget.entries.firstOrNull { it.wireId == trimmed }
    }

    fun defaultActionsFor(kind: WidgetKind): List<WidgetActionTarget> = when (kind) {
        WidgetKind.QUICK_ACTIONS -> listOf(WidgetActionTarget.SEND_FILE)
        WidgetKind.DEVICE_CARD_2X2,
        WidgetKind.DROP_TARGET,
        WidgetKind.BULLETIN_BOARD -> emptyList()
    }
}

data class CachedBatteryReading(
    val levelPercent: Int,
    val recordedAtEpochMs: Long,
    val chargingState: String = ""
)

object WidgetBatteryFormatter {
    fun formatPillText(
        reading: CachedBatteryReading?,
        zoneId: String = TimeUtils.DEFAULT_ZONE_ID
    ): String? {
        if (reading == null || reading.levelPercent !in 0..100 || reading.recordedAtEpochMs <= 0L) {
            return null
        }
        val time = TimeUtils.noteListStamp(reading.recordedAtEpochMs, zoneId).timeLabel
        return "${reading.levelPercent}% · $time"
    }
}
