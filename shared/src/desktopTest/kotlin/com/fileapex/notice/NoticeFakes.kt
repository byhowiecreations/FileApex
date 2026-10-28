package com.fileapex.notice

import com.fileapex.data.settings.SettingsKvStore

/** In-memory store with the desktop preferences rule that one string value holds at most 8192 characters. */
class MapKvStore : SettingsKvStore {
    val map = linkedMapOf<String, Any>()
    override fun contains(key: String) = map.containsKey(key)
    override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun getString(key: String, default: String) = map[key] as? String ?: default
    override fun putString(key: String, value: String) {
        require(value.length <= 8192) { "value too long for the desktop preferences backend" }
        map[key] = value
    }
    override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
    override fun putInt(key: String, value: Int) { map[key] = value }
    override fun getLong(key: String, default: Long) = map[key] as? Long ?: default
    override fun putLong(key: String, value: Long) { map[key] = value }
    override fun clear() = map.clear()
    override fun keys(prefix: String) = map.keys.filter { it.startsWith(prefix) }
    override fun remove(key: String) { map.remove(key) }
}

class FakeTransport(private val responses: MutableList<NoticeHttpResponse>) : NoticeTransport {
    val requests = mutableListOf<Pair<String, String?>>()
    override suspend fun get(url: String, etag: String?): NoticeHttpResponse {
        requests += url to etag
        return if (responses.size > 1) responses.removeAt(0) else responses.first()
    }
}
