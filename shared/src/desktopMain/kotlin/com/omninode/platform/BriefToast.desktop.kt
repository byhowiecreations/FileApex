package com.omninode.platform

actual object BriefToast {
    actual fun show(message: String) {
        // Desktop has no Toast API; log for diagnosis. Settings status text still updates.
        println("BriefToast: $message")
    }
}
