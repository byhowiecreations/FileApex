package com.fileapex.iroh

/** Names the path an Iroh connection uses: `direct`, `relay`, or `custom_relay` for the self-hosted relay. */
object IrohPathKind {
    class Selected(val isRelay: Boolean, val remoteAddr: String)

    /** The words the Iroh log uses for a path kind from [of]. */
    fun logLabel(kind: String?): String = when (kind) {
        "direct" -> "direct"
        "relay" -> "relay"
        "custom_relay" -> "custom relay"
        else -> "no live path"
    }

    fun of(selected: List<Selected>, customRelayUrl: String?): String? {
        if (selected.isEmpty()) return null
        val relay = selected.firstOrNull { it.isRelay } ?: return "direct"
        val host = customRelayUrl?.substringAfter("://")?.substringBefore('/')?.substringBefore(':')?.lowercase()
        return if (!host.isNullOrEmpty() && relay.remoteAddr.lowercase().contains(host)) "custom_relay" else "relay"
    }
}
