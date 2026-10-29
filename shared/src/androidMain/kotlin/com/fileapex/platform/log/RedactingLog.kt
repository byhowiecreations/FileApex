package com.fileapex.platform.log

import com.fileapex.util.LogRedactor

/** The `android.util.Log` calls the app uses, with the message and any exception text redacted first. */
object Log {
    fun d(tag: String, msg: String): Int = android.util.Log.d(tag, LogRedactor.redact(msg))

    fun d(tag: String, msg: String, tr: Throwable?): Int = android.util.Log.d(tag, withThrowable(msg, tr))

    fun i(tag: String, msg: String): Int = android.util.Log.i(tag, LogRedactor.redact(msg))

    fun i(tag: String, msg: String, tr: Throwable?): Int = android.util.Log.i(tag, withThrowable(msg, tr))

    fun w(tag: String, msg: String): Int = android.util.Log.w(tag, LogRedactor.redact(msg))

    fun w(tag: String, msg: String, tr: Throwable?): Int = android.util.Log.w(tag, withThrowable(msg, tr))

    fun w(tag: String, tr: Throwable?): Int = android.util.Log.w(tag, withThrowable("", tr))

    fun e(tag: String, msg: String): Int = android.util.Log.e(tag, LogRedactor.redact(msg))

    fun e(tag: String, msg: String, tr: Throwable?): Int = android.util.Log.e(tag, withThrowable(msg, tr))

    private fun withThrowable(msg: String, tr: Throwable?): String {
        val trace = if (tr == null) "" else android.util.Log.getStackTraceString(tr)
        return LogRedactor.redact(if (trace.isEmpty()) msg else "$msg\n$trace")
    }
}
