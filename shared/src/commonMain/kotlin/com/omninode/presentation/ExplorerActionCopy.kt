package com.omninode.presentation

/**
 * User-facing explorer selection / send action strings (SSOT for toolbar and picker copy).
 * Internal types remain MultiCopy* in the domain layer.
 */
object ExplorerActionCopy {
    const val SEND_TO_ACTION = "Send To"
    const val COPY_ACTION = "Copy"

    const val SELECTION_MODE_HELPER =
        "Copy = save for Paste later · Send To = send files to paired devices now"

    const val SEND_TO_INTRO_TITLE = "Send To"
    const val SEND_TO_INTRO_BODY =
        "Send To delivers the selected file(s) to one or more paired devices immediately. " +
            "Copy only stores a clipboard entry for Paste on this device."

    const val SEND_TO_PICKER_TITLE = "Send To devices"
    const val SEND_TO_PICKER_CONFIRM = "Send To selected devices"
    const val SEND_TO_IN_PROGRESS = "Sending to devices…"

    const val ERROR_SELECT_FILES = "Select at least one file to send"
    const val ERROR_SEND_FAILED = "Send To failed for all destinations"
    fun sendFinishedWithErrors(count: Int): String = "Send To finished with $count error(s)"
}
