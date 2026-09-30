package com.fileapex.platform

expect object Diagnostics {
    /**
     * Record an operational event in the in-memory ring buffer (capturing up to 200 events).
     */
    fun log(event: String)

    /**
     * Return a snapshot list of current buffered operational events.
     */
    fun dumpLogs(): List<String>

    /**
     * Export diagnostic logs and launch an email client intent to byhowiecreations@gmail.com.
     * Wrapped in try-catch to safely handle devices with no email client installed.
     */
    fun sendFeedbackEmail(): Boolean

    /**
     * Launch Chrome Custom Tab (or browser) with pre-filled Google Form URL including telemetry.
     */
    fun openAnonymousFeedbackForm()

    /**
     * Launch Chrome Custom Tab (or browser) pointing to the GitHub Issues tracker.
     */
    fun openGitHubIssuesTracker()
}
