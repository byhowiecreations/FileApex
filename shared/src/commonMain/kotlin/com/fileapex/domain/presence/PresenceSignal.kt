package com.fileapex.domain.presence

import kotlinx.serialization.Serializable

/** What a peer can tell another about itself on `POST /api/v1/presence`. Only a goodbye today; anything else is refused. */
enum class PresenceSignal(val wire: String) {
    Leaving("leaving");

    companion object {
        fun parse(raw: String): PresenceSignal? = entries.firstOrNull { it.wire == raw }
    }
}

@Serializable
data class PresenceMessage(val state: String)
