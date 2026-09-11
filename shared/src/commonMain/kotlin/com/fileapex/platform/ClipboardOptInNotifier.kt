package com.fileapex.platform

const val EXTRA_SHOW_CLIPBOARD_OPT_IN = "com.fileapex.extra.SHOW_CLIPBOARD_OPT_IN"
const val EXTRA_CLIPBOARD_OPT_IN_SENDER = "com.fileapex.extra.CLIPBOARD_OPT_IN_SENDER"

expect fun notifyClipboardOptInRequested(senderDeviceName: String)
