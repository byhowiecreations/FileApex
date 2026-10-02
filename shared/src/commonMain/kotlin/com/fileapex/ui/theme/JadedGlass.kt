package com.fileapex.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState

val LocalJadedHazeState = staticCompositionLocalOf<HazeState?> { null }

@Composable
fun rememberJadedHazeState(): HazeState = rememberHazeState()

@Composable
fun ProvideJadedHaze(
    state: HazeState?,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalJadedHazeState provides state, content = content)
}
