package com.fileapex.ui.explorer

import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fileapex.i18n.stringRes
import com.fileapex.ui.theme.FileApexMonoStyle

/** What the viewer is showing. */
internal sealed interface ViewerBody {
    data object Loading : ViewerBody
    data object Unsupported : ViewerBody
    data class Image(val bitmap: ImageBitmap) : ViewerBody
    data class Pages(val pages: List<ImageBitmap>) : ViewerBody
    data class Text(val text: String) : ViewerBody
}

/**
 * Full-window file viewer: zoomable images, numbered monospace text, paged PDFs, previous and next file, and the
 * actions for the file. Keyboard: Escape closes, Left and Right change file, plus, minus and 0 zoom an image.
 */
@Composable
internal fun FileViewer(
    name: String,
    sizeText: String,
    position: Pair<Int, Int>?,
    body: ViewerBody,
    canDownload: Boolean,
    downloading: Boolean,
    onDownload: () -> Unit,
    onClose: () -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?
) {
    val focus = remember { FocusRequester() }
    var zoomCommand by remember { mutableStateOf<ZoomCommand?>(null) }
    var wrap by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Escape -> { onClose(); true }
                        Key.DirectionLeft -> onPrevious?.let { it(); true } ?: false
                        Key.DirectionRight -> onNext?.let { it(); true } ?: false
                        Key.Plus, Key.Equals, Key.NumPadAdd -> { zoomCommand = ZoomCommand.In; true }
                        Key.Minus, Key.NumPadSubtract -> { zoomCommand = ZoomCommand.Out; true }
                        Key.Zero, Key.NumPad0 -> { zoomCommand = ZoomCommand.Reset; true }
                        else -> false
                    }
                },
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ViewerBar(
                    name = name,
                    sizeText = sizeText,
                    position = position,
                    showWrap = body is ViewerBody.Text,
                    wrap = wrap,
                    onWrap = { wrap = it },
                    canDownload = canDownload,
                    downloading = downloading,
                    onDownload = onDownload,
                    onClose = onClose,
                    onPrevious = onPrevious,
                    onNext = onNext
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when (body) {
                        ViewerBody.Loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(12.dp))
                            TextButton(onClick = onClose) { Text(stringRes("cancel")) }
                        }
                        ViewerBody.Unsupported -> Box(
                            modifier = Modifier.fillMaxSize().swipeToChangeFile(onPrevious, onNext),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringRes("no_preview"),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                        is ViewerBody.Image -> ZoomableImage(body.bitmap, name, zoomCommand, onPrevious, onNext) { zoomCommand = null }
                        is ViewerBody.Pages -> PageList(body.pages, name, Modifier.swipeToChangeFile(onPrevious, onNext))
                        is ViewerBody.Text -> NumberedText(body.text, wrap, if (wrap) Modifier.swipeToChangeFile(onPrevious, onNext) else Modifier)
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerBar(
    name: String,
    sizeText: String,
    position: Pair<Int, Int>?,
    showWrap: Boolean,
    wrap: Boolean,
    onWrap: (Boolean) -> Unit,
    canDownload: Boolean,
    downloading: Boolean,
    onDownload: () -> Unit,
    onClose: () -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringRes("close")) }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(sizeText.takeIf { it.isNotBlank() }, position?.let { "${it.first} / ${it.second}" }).joinToString("  •  ")
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        if (onPrevious != null) {
            IconButton(onClick = onPrevious) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringRes("viewer_previous")) }
        }
        if (onNext != null) {
            IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringRes("viewer_next")) }
        }
        if (showWrap) {
            TextButton(onClick = { onWrap(!wrap) }) { Text(stringRes(if (wrap) "viewer_wrap_off" else "viewer_wrap_on")) }
        }
        if (canDownload) {
            TextButton(onClick = onDownload, enabled = !downloading) {
                Text(if (downloading) stringRes("downloading") else stringRes("download"))
            }
        }
    }
}

private enum class ZoomCommand { In, Out, Reset }

@Composable
private fun ZoomableImage(
    bitmap: ImageBitmap,
    name: String,
    command: ZoomCommand?,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onCommandHandled: () -> Unit
) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Size.Zero) }
    fun zoomTo(next: Float) {
        scale = FileViewerLogic.clampScale(next)
        offset = FileViewerLogic.clampOffset(offset, scale, viewport)
    }
    LaunchedEffect(command) {
        when (command) {
            ZoomCommand.In -> zoomTo(scale * 1.25f)
            ZoomCommand.Out -> zoomTo(scale / 1.25f)
            ZoomCommand.Reset -> { scale = 1f; offset = Offset.Zero }
            null -> return@LaunchedEffect
        }
        onCommandHandled()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(bitmap, onPrevious, onNext) {
                val thresholdPx = 96.dp.toPx()
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var swipeDragX = 0f
                    var multiTouchSeen = false
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1) multiTouchSeen = true
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        if (multiTouchSeen || !FileViewerLogic.canSwipeImage(scale) || zoomChange != 1f) {
                            scale = FileViewerLogic.clampScale(scale * zoomChange)
                            offset = FileViewerLogic.clampOffset(offset + panChange, scale, viewport)
                            if (zoomChange != 1f || panChange != Offset.Zero) {
                                event.changes.forEach { it.consume() }
                            }
                        } else if (onPrevious != null || onNext != null) {
                            swipeDragX += panChange.x
                            if (panChange.x != 0f) {
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (!multiTouchSeen && FileViewerLogic.canSwipeImage(scale)) {
                        when (FileViewerLogic.swipe(swipeDragX, thresholdPx)) {
                            FileViewerLogic.Swipe.Previous -> onPrevious?.invoke()
                            FileViewerLogic.Swipe.Next -> onNext?.invoke()
                            null -> Unit
                        }
                    }
                }
            }
            .pointerInput(bitmap) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else zoomTo(2.5f)
                })
            }
            .pointerInput(bitmap) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (dy != 0f) zoomTo(scale * if (dy < 0f) 1.1f else 1f / 1.1f)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
        )
    }
}

@Composable
private fun PageList(pages: List<ImageBitmap>, name: String, modifier: Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items(pages) { page ->
            Image(
                bitmap = page,
                contentDescription = name,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth()
            )
        }
    }
}

@Composable
private fun NumberedText(text: String, wrap: Boolean, modifier: Modifier) {
    val lines = remember(text) { FileViewerLogic.lines(text) }
    val digits = FileViewerLogic.gutterDigits(lines.size)
    val sideScroll = rememberScrollState()
    SelectionContainer(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().then(if (wrap) Modifier else Modifier.horizontalScroll(sideScroll)),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            itemsIndexed(lines) { index, line ->
                Row(modifier = (if (wrap) Modifier.fillMaxWidth() else Modifier).padding(vertical = 1.dp)) {
                    Text(
                        text = (index + 1).toString().padStart(digits),
                        style = FileApexMonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.semantics { contentDescription = "" }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = line, style = FileApexMonoStyle, softWrap = wrap)
                }
            }
        }
    }
}

/** Dragging sideways changes to the next or previous file, for content that does not scroll sideways itself. */
private fun Modifier.swipeToChangeFile(onPrevious: (() -> Unit)?, onNext: (() -> Unit)?): Modifier =
    if (onPrevious == null && onNext == null) this else pointerInput(onPrevious, onNext) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                when (FileViewerLogic.swipe(total, thresholdPx = 96.dp.toPx())) {
                    FileViewerLogic.Swipe.Previous -> onPrevious?.invoke()
                    FileViewerLogic.Swipe.Next -> onNext?.invoke()
                    null -> Unit
                }
            },
            onHorizontalDrag = { _, amount -> total += amount }
        )
    }
