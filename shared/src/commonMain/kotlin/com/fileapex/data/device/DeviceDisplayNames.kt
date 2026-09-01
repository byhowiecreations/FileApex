package com.fileapex.data.device

/**
 * User-assigned display names must survive pairing/heartbeat/cloud seed of the
 * hardware default (manufacturer + model). Incoming custom names still win so a
 * rename performed on the device itself replicates.
 */
object DeviceDisplayNames {
    const val FALLBACK = "Paired Device"

    private val placeholders = setOf(
        "paired device",
        "cloud device",
        "android device",
        "this device"
    )

    fun isPlaceholder(name: String): Boolean = isFactory(name, "", "")

    fun resolve(
        incomingName: String,
        rosterName: String?,
        make: String = "",
        model: String = ""
    ): String {
        val incoming = incomingName.trim()
        val roster = rosterName.orEmpty().trim()
        if (roster.isNotEmpty() && !isPlaceholder(roster)) {
            when {
                incoming.isEmpty() || isPlaceholder(incoming) -> return roster
                (make.isNotEmpty() || model.isNotEmpty()) &&
                    isFactory(incoming, make, model) &&
                    !isFactory(roster, make, model) -> return roster
            }
        }
        if (incoming.isNotEmpty() && !isPlaceholder(incoming)) return incoming
        if (roster.isNotEmpty() && !isPlaceholder(roster)) return roster
        return FALLBACK
    }

    fun isFactory(name: String, make: String, model: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return true
        if (n.lowercase() in placeholders) return true
        val mk = make.trim()
        val md = model.trim()
        if (md.isNotEmpty() && n.equals(md, ignoreCase = true)) return true
        if (mk.isNotEmpty() && md.isNotEmpty() && n.equals("$mk $md", ignoreCase = true)) {
            return true
        }
        return false
    }

    fun merge(
        existingName: String,
        incomingName: String,
        make: String,
        model: String
    ): String {
        val existing = existingName.trim()
        val incoming = incomingName.trim()
        if (incoming.isEmpty()) return existing
        if (existing.isEmpty()) return incoming
        if (existing.equals(incoming, ignoreCase = true)) return existing
        val incomingFactory = isFactory(incoming, make, model)
        val existingFactory = isFactory(existing, make, model)
        if (incomingFactory && !existingFactory) return existing
        return incoming
    }
}
