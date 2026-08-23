package com.fileapex.presentation

import com.fileapex.i18n.AppI18n

object ExplorerActionCopy {
    val SEND_TO_ACTION get() = AppI18n.t("send_to")
    val COPY_ACTION get() = AppI18n.t("copy_action")

    val SELECTION_MODE_HELPER get() = AppI18n.t("selection_mode_helper")

    val SEND_TO_INTRO_TITLE get() = AppI18n.t("send_to")
    val SEND_TO_INTRO_BODY get() = AppI18n.t("send_to_intro_body")

    val SEND_TO_PICKER_TITLE get() = AppI18n.t("send_to_picker_title")
    val SEND_TO_PICKER_CONFIRM get() = AppI18n.t("send_to_picker_confirm")
    val SEND_TO_IN_PROGRESS get() = AppI18n.t("send_to_in_progress")

    val ERROR_SELECT_FILES get() = AppI18n.t("error_select_files")
    val ERROR_SEND_FAILED get() = AppI18n.t("error_send_failed")
    fun sendFinishedWithErrors(count: Int): String = AppI18n.t("send_to_finished_errors", count)
}
