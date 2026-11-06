package com.fileapex.platform

import android.content.SharedPreferences
import com.fileapex.data.seal.StringSlots

internal class PrefsStringSlots(private val prefs: SharedPreferences) : StringSlots {
    // Older versions kept some values as numbers (the Direct Share counts); getString would throw on those.
    override fun get(key: String): String? = prefs.all[key]?.toString()
    override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun remove(key: String) = prefs.edit().remove(key).apply()
    override fun keys(): Set<String> = prefs.all.keys.toSet()
}
