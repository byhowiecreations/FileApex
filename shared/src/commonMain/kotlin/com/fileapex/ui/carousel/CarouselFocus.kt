package com.fileapex.ui.carousel

/** The device last centred in the Carousel this run, so leaving and returning to the Devices screen keeps its place. */
object CarouselFocus {
    @Volatile
    var lastDeviceId: String? = null
}
