package com.fileapex.ui.carousel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which Carousel card shows its back. Held here because a press anywhere in the app has to flip it back. */
object CarouselFlip {
    private val flipped = MutableStateFlow<String?>(null)
    val flippedId: StateFlow<String?> = flipped.asStateFlow()

    /** Bounds of the flipped card in root coordinates; a press inside them keeps it flipped. */
    @Volatile
    var cardBounds: Rect? = null

    fun toggle(deviceId: String) {
        flipped.value = if (flipped.value == deviceId) null else deviceId
        if (flipped.value == null) cardBounds = null
    }

    fun unflip() {
        flipped.value = null
        cardBounds = null
    }

    fun outsideCard(pressInRoot: Offset): Boolean = cardBounds?.contains(pressInRoot) != true
}

/** Flips the open card back when a press lands anywhere outside it. Does not consume the press. */
@Composable
fun Modifier.carouselUnflipOnOutsidePress(): Modifier {
    var origin by remember { mutableStateOf(Offset.Zero) }
    return this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press && CarouselFlip.flippedId.value != null) {
                        val press = event.changes.firstOrNull()?.position ?: continue
                        if (CarouselFlip.outsideCard(press + origin)) CarouselFlip.unflip()
                    }
                }
            }
        }
}
