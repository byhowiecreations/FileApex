package com.fileapex.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.fileapex.i18n.stringRes
import com.fileapex.ui.theme.LocalJadedHazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

internal val ExpandableSearchRowHeight = 44.dp

/**
 * Shared sliding search pill that starts as a circular magnifying-glass icon alongside [leading]
 * content and expands smoothly across the row when tapped, shrinking back when closed or blurred empty.
 */
@Composable
internal fun ExpandableSearchPillRow(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    leading: @Composable (modifier: Modifier, compact: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    collapseLeadingCompletely: Boolean = false,
    constrainHeight: Boolean = true,
    useJadedStyle: Boolean = false,
    useLightChrome: Boolean = false,
    searchPillModifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    var wasFocused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val showField = open || query.isNotEmpty()
    val fraction by animateFloatAsState(
        targetValue = if (showField) 1f else 0f,
        animationSpec = tween(durationMillis = 260)
    )
    LaunchedEffect(open) {
        if (open) {
            withFrameNanos { }
            focus.requestFocus()
        } else {
            wasFocused = false
        }
    }

    val pill = RoundedCornerShape(50)
    val ink = when {
        useJadedStyle -> Color(0xFFE4EEEF)
        useLightChrome -> Color.White
        else -> MaterialTheme.colorScheme.onSurface
    }
    val hintColor = when {
        useJadedStyle -> Color(0xFFB7C4C6)
        useLightChrome -> Color.White.copy(alpha = 0.75f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val iconColor = when {
        useJadedStyle -> Color(0xFFB7C4C6)
        useLightChrome -> Color.White
        else -> MaterialTheme.colorScheme.onSurface
    }

    BoxWithConstraints(modifier = if (constrainHeight) modifier.height(ExpandableSearchRowHeight) else modifier) {
        val baseGap = 8.dp
        val collapsedWidth = ExpandableSearchRowHeight.coerceAtMost(maxWidth)
        val expandedWidth = if (collapseLeadingCompletely) {
            maxWidth
        } else {
            (maxWidth - collapsedWidth - baseGap).coerceAtLeast(collapsedWidth)
        }
        val searchWidth = lerp(collapsedWidth, expandedWidth, fraction)
        val gap = if (collapseLeadingCompletely) baseGap * (1f - fraction) else baseGap
        val leadingWidth = (maxWidth - gap - searchWidth).coerceAtLeast(0.dp)

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingWidth > 1.dp) {
                Box(
                    modifier = Modifier
                        .width(leadingWidth)
                        .then(if (constrainHeight) Modifier.height(ExpandableSearchRowHeight) else Modifier)
                        .then(
                            if (!collapseLeadingCompletely) {
                                Modifier.clip(pill).clickable(enabled = showField) {
                                    onQueryChange("")
                                    wasFocused = false
                                    open = false
                                }
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.CenterStart
                ) {
                    leading(if (constrainHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth(), leadingWidth < 170.dp)
                }
            }
            if (gap > 0.dp) {
                Spacer(modifier = Modifier.width(gap))
            }

            val haze = if (useJadedStyle) LocalJadedHazeState.current else null
            val pillVisualModifier = when {
                useJadedStyle -> {
                    Modifier
                        .then(
                            if (haze != null) {
                                Modifier.hazeEffect(
                                    state = haze,
                                    style = HazeStyle(
                                        backgroundColor = Color.Transparent,
                                        tints = listOf(HazeTint(Color.White.copy(alpha = 0.09f))),
                                        blurRadius = 24.dp,
                                        noiseFactor = 0.04f
                                    )
                                )
                            } else {
                                Modifier
                            }
                        )
                        .background(Color.White.copy(alpha = 0.09f))
                        .border(
                            width = if (showField) 1.5.dp else 1.dp,
                            brush = Brush.verticalGradient(
                                0f to Color.White.copy(alpha = if (showField) 0.45f else 0.28f),
                                1f to Color.White.copy(alpha = if (showField) 0.18f else 0.10f)
                            ),
                            shape = pill
                        )
                }
                useLightChrome -> {
                    Modifier
                        .background(Color.White.copy(alpha = if (showField) 0.14f else 0.08f))
                        .border(
                            width = if (showField) 1.5.dp else 1.dp,
                            color = Color.White.copy(alpha = if (showField) 0.9f else 0.45f),
                            shape = pill
                        )
                }
                else -> {
                    Modifier.border(
                        width = if (showField) 2.dp else 1.dp,
                        color = if (showField) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = pill
                    )
                }
            }

            Row(
                modifier = searchPillModifier
                    .width(searchWidth)
                    .height(ExpandableSearchRowHeight)
                    .clip(pill)
                    .then(pillVisualModifier)
                    .clickable(enabled = !showField) { open = true }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = placeholder,
                    tint = iconColor,
                    modifier = Modifier.size(22.dp)
                )
                if (showField) {
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = ink),
                        cursorBrush = SolidColor(if (useJadedStyle || useLightChrome) ink else MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focus)
                            .onFocusChanged { state ->
                                if (state.isFocused) {
                                    wasFocused = true
                                } else if (wasFocused && query.isEmpty()) {
                                    wasFocused = false
                                    open = false
                                }
                            }
                            .onPreviewKeyEvent { event ->
                                if (event.key == Key.Escape && event.type == KeyEventType.KeyUp) {
                                    onQueryChange("")
                                    wasFocused = false
                                    open = false
                                    true
                                } else {
                                    false
                                }
                            },
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) {
                                    Text(
                                        text = placeholder,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = hintColor,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                inner()
                            }
                        }
                    )
                    if ((query.isNotEmpty() || collapseLeadingCompletely) && fraction > 0.2f) {
                        IconButton(
                            onClick = {
                                if (query.isNotEmpty()) {
                                    onQueryChange("")
                                } else {
                                    wasFocused = false
                                    open = false
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringRes("clear"),
                                tint = hintColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
