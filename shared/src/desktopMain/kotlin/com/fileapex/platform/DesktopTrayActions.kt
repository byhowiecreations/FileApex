package com.fileapex.platform

import com.fileapex.di.FileApexServices
import com.fileapex.domain.clipboard.ClipboardShareCoordinator
import com.fileapex.i18n.AppI18n
import com.fileapex.presentation.devices.PeerBatteryFetch
import com.fileapex.util.cancellableCatching
import java.time.LocalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Battery results of the last Check Batteries tap, shared by the Mac and Windows trays. Nothing runs until the user taps. */
object DesktopTrayBatteries {
    private val state = MutableStateFlow<Map<String, TrayBatteryReading>>(emptyMap())
    val readings: StateFlow<Map<String, TrayBatteryReading>> = state.asStateFlow()

    suspend fun check(devices: List<DesktopTrayDeviceSnapshot>) {
        state.value = readTrayBatteries(devices, PeerBatteryFetch::fetch, LocalTime::now)
    }
}

/** One message to show after a tray action, `isError` when it failed. */
data class TrayActionMessage(val text: String, val isError: Boolean)

/** Sends this computer's clipboard to the given devices through the same path as the CLI and the Devices screen. */
suspend fun sendTrayClipboard(deviceIds: List<String>): TrayActionMessage {
    val ids = deviceIds.filterNot(::isFriendTrayId)
    if (ids.isEmpty()) return TrayActionMessage(AppI18n.t("clipboard_failed"), true)
    val names = mutableListOf<String>()
    for (id in ids) {
        val result = cancellableCatching { ClipboardShareCoordinator.sendToDevice(id) }
        val response = result.getOrNull()
        if (response == null) {
            val reason = result.exceptionOrNull()?.message.orEmpty().ifBlank { AppI18n.t("clipboard_failed") }
            return TrayActionMessage(reason, true)
        }
        names += response.recipientDeviceName
    }
    return TrayActionMessage(AppI18n.t("clipboard_send_success", names.joinToString(", ")), false)
}

/** Posts a note from the tray to the Bulletin Board of every paired device. [post] is replaced in tests. */
suspend fun postTrayNote(
    text: String,
    post: suspend (String) -> Unit = { note ->
        val engine = FileApexServices.bulletinSyncEngineOrNull() ?: error(AppI18n.t("tray_post_unavailable"))
        engine.ingestSharedText(note)
    }
): TrayActionMessage {
    val note = text.trim()
    if (note.isEmpty()) return TrayActionMessage(AppI18n.t("tray_post_hint"), true)
    val result = cancellableCatching { post(note) }
    return if (result.isSuccess) {
        TrayActionMessage(AppI18n.t("posted_to_bulletin"), false)
    } else {
        TrayActionMessage(result.exceptionOrNull()?.message.orEmpty().ifBlank { AppI18n.t("tray_post_unavailable") }, true)
    }
}
