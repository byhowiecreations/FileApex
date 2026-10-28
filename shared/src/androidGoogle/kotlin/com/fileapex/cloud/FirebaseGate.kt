package com.fileapex.cloud

import android.content.Context
import android.util.Log
import com.fileapex.data.settings.androidAppContextOrNull
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Firebase starts only for an install that uses the Google link (its auto-start provider is removed from the manifest).
 * Every call site goes through here, so a feature that needs Firebase starts it on first use.
 */
object FirebaseGate {
    private const val PREFS = "fileapex_firebase_gate"
    private const val KEY = "linked"
    private const val TAG = "FirebaseGate"

    fun ensure(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context)
    }

    private fun app(): FirebaseApp {
        val context = androidAppContextOrNull() ?: error("Android context not ready")
        ensure(context)
        return FirebaseApp.getInstance()
    }

    fun auth(): FirebaseAuth = FirebaseAuth.getInstance(app())

    fun firestore(): FirebaseFirestore = FirebaseFirestore.getInstance(app())

    fun messaging(): FirebaseMessaging = app().get(FirebaseMessaging::class.java)

    /** The configured keys, read from the app resources without starting Firebase (the REST paths only need the values). */
    fun options(): FirebaseOptions {
        val context = androidAppContextOrNull() ?: error("Android context not ready")
        return FirebaseOptions.fromResource(context) ?: error("Firebase options missing from resources")
    }

    /** True when asking Firebase about a session would only start it for nothing: known unlinked and not running. */
    fun skipSessionCheck(): Boolean {
        val context = androidAppContextOrNull() ?: return false
        return FirebaseGatePolicy.canSkipSessionCheck(prefs(context).getString(KEY, null), FirebaseApp.getApps(context).isNotEmpty())
    }

    /** First thing in Application.onCreate: skips Firebase on an install already known to be unlinked. */
    fun initIfLinkedAtLaunch(context: Context) {
        if (FirebaseGatePolicy.shouldInitAtLaunch(prefs(context).getString(KEY, null))) ensure(context)
    }

    fun onLinkChanged(context: Context, linked: Boolean) {
        val previous = prefs(context).getString(KEY, null)
        prefs(context).edit().putString(KEY, linked.toString()).apply()
        if (linked) ensure(context)
        if (FirebaseGatePolicy.shouldDeleteTokenOnChange(previous, linked)) {
            runCatching {
                ensure(context)
                messaging().deleteToken()
            }.onFailure { Log.w(TAG, "push token not removed: ${it.javaClass.simpleName}") }
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
