package com.fileapex.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

enum class MotionKind { Fade, Move, Scale }

/**
 * The only place that picks animation timing. UI that the user triggers takes at most [MAX_UI_MILLIS], an exit is
 * faster than its enter, and with reduced motion movement and scale are instant while fades stay short.
 */
object FileApexMotion {
    const val PRESS_MILLIS = 120
    const val EXIT_MILLIS = 150
    const val ENTER_MILLIS = 220
    const val MAX_UI_MILLIS = 300
    const val REDUCED_FADE_MILLIS = 120
    private const val WINDOW_MOVE_MILLIS = 220L

    /** Ease-out curve shared with the desktop window animation. */
    val EaseOut = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    fun <T> enter(kind: MotionKind, reduced: Boolean): FiniteAnimationSpec<T> = when {
        !reduced -> tween(ENTER_MILLIS, easing = EaseOut)
        kind == MotionKind.Fade -> tween(REDUCED_FADE_MILLIS, easing = EaseOut)
        else -> snap()
    }

    fun <T> exit(kind: MotionKind, reduced: Boolean): FiniteAnimationSpec<T> = when {
        !reduced -> tween(EXIT_MILLIS, easing = EaseOut)
        kind == MotionKind.Fade -> tween(REDUCED_FADE_MILLIS, easing = EaseOut)
        else -> snap()
    }

    fun <T> press(reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(PRESS_MILLIS, easing = EaseOut)

    /** Springs are for dragging and for things that follow a finger. */
    fun <T> drag(
        reduced: Boolean,
        dampingRatio: Float = Spring.DampingRatioNoBouncy,
        stiffness: Float = Spring.StiffnessMediumLow,
        visibilityThreshold: T? = null
    ): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio, stiffness, visibilityThreshold)

    /** A long animation that is part of the look (rings, particles, a card flip); it simply does not run when reduced. */
    fun <T> decorative(durationMillis: Int, reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(durationMillis, easing = EaseOut)

    fun windowMoveMillis(reduced: Boolean): Long = if (reduced) 0L else WINDOW_MOVE_MILLIS

    fun fadeInSpec(reduced: Boolean): EnterTransition = fadeIn(enter(MotionKind.Fade, reduced))

    fun fadeOutSpec(reduced: Boolean): ExitTransition = fadeOut(exit(MotionKind.Fade, reduced))

    fun slideUpIn(reduced: Boolean): EnterTransition =
        slideInVertically(enter<IntOffset>(MotionKind.Move, reduced)) { it } + fadeInSpec(reduced)

    fun slideDownOut(reduced: Boolean): ExitTransition =
        slideOutVertically(exit<IntOffset>(MotionKind.Move, reduced)) { it } + fadeOutSpec(reduced)

    fun expandIn(reduced: Boolean): EnterTransition =
        expandVertically(enter<IntSize>(MotionKind.Move, reduced)) + fadeInSpec(reduced)

    fun shrinkOut(reduced: Boolean): ExitTransition =
        shrinkVertically(exit<IntSize>(MotionKind.Move, reduced)) + fadeOutSpec(reduced)
}
