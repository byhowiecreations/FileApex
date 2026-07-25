package com.fileapex.platform

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

private lateinit var toastContext: Context

fun initAndroidBriefToast(context: Context) {
    toastContext = context.applicationContext
}

actual object BriefToast {
    actual fun show(message: String) {
        if (!::toastContext.isInitialized) {
            println("BriefToast: skipped — not initialized ($message)")
            return
        }
        val text = message
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(toastContext, text, Toast.LENGTH_SHORT).show()
        }
    }
}
