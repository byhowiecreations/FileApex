package com.fileapex.drop

/**
 * Whether the owner accepted that a shared link goes through a relay server. Unasked until the first share is tried; a decline
 * hides the action and allows nothing until the owner accepts in Settings. The number is what Settings stores.
 */
enum class GuestLinkConsent(val stored: Int) {
    Unasked(0),
    Accepted(1),
    Declined(2);

    /** The action may be shown (a decline hides it). */
    val offered: Boolean get() = this != Declined

    /** The consent screen must come before the first link. */
    val mustAsk: Boolean get() = this == Unasked

    /** A link may be made. */
    val allowed: Boolean get() = this == Accepted

    companion object {
        fun fromStored(value: Int): GuestLinkConsent = entries.firstOrNull { it.stored == value } ?: Unasked
    }
}
