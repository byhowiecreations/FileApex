package com.fileapex.cloud

/** Decisions about when Firebase starts; the mirror is "true" or "false" once this install has seen its Google link state. */
object FirebaseGatePolicy {
    /** An install with no mirror yet (first launch after the update) starts Firebase as before, so a linked user loses nothing. */
    fun shouldInitAtLaunch(mirror: String?): Boolean = mirror != "false"

    /** The launch check for a leftover session need not start Firebase on an install already known to be unlinked. */
    fun canSkipSessionCheck(mirror: String?, firebaseRunning: Boolean): Boolean = mirror == "false" && !firebaseRunning

    /** Unlinking removes the push token so Google stops waking an app that no longer uses it. */
    fun shouldDeleteTokenOnChange(previous: String?, linked: Boolean): Boolean = !linked && previous == "true"
}
