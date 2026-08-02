package com.fileapex.platform

/** Short-lived Toast / transient status text for brief user feedback. */
expect object BriefToast {
    fun show(message: String)
}
