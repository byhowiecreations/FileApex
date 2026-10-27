package com.fileapex.data.settings

/**
 * The Carousel theme only exists on a Mac with at least [MIN_PEERS] other devices, because a device never
 * shows itself and the cards left and right of the centre must be different devices.
 */
object CarouselGate {
    const val MIN_PEERS = 3
    private const val MAC_PLATFORM = "Mac"

    fun available(platformLabel: String, peerCount: Int): Boolean =
        platformLabel == MAC_PLATFORM && peerCount >= MIN_PEERS

    /** The theme to draw: a stored Carousel that is not available shows [fallback] and is kept for when it is. */
    fun effective(stored: AppTheme, available: Boolean, fallback: AppTheme): AppTheme =
        if (stored == AppTheme.CAROUSEL && !available) fallback.takeIf { it != AppTheme.CAROUSEL } ?: AppTheme.DEFAULT else stored
}
