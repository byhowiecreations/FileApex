package com.fileapex.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposePanel
import com.fileapex.i18n.AppI18n
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

/**
 * Windows Drop Files panel matching macOS DropBoxContentView parity.
 * All state is touched on the Swing thread; the disk is read on [Dispatchers.IO].
 */
object DesktopWindowsDropBox {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var frame: JFrame? = null
    private val stagingState = DesktopDropBoxStagingState()
    private var onSendCallback: ((deviceIds: List<String>, paths: List<String>) -> Unit)? = null
    private var persistTimer: Timer? = null

    private var stagedEntriesState by mutableStateOf<List<DesktopStagedEntry>>(emptyList())
    private var isSendingState by mutableStateOf(false)
    private var isPinnedState by mutableStateOf(false)
    private var isDragOverState by mutableStateOf(false)
    private var destinationSubtitleState by mutableStateOf("")

    fun show(
        deviceIds: List<String>,
        singleDeviceName: String? = null,
        onSend: (deviceIds: List<String>, paths: List<String>) -> Unit
    ) {
        require(deviceIds.isNotEmpty())
        SwingUtilities.invokeLater {
            this.onSendCallback = onSend
            stagingState.targetDeviceIds = deviceIds
            stagingState.singleDeviceName = singleDeviceName
            stagingState.clear()
            syncStateToCompose()
            ensureFrame()

            val window = frame ?: return@invokeLater
            applySavedOrDefaultBounds(window)
            window.isVisible = true
            window.toFront()
            window.requestFocus()
        }
    }

    fun onSendFinished() {
        SwingUtilities.invokeLater {
            isSendingState = false
            stagingState.isSending = false
            if (!isPinnedState) {
                close()
            } else {
                stagingState.clear()
                syncStateToCompose()
            }
        }
    }

    fun close() {
        SwingUtilities.invokeLater {
            persistFrameNow()
            frame?.isVisible = false
            stagingState.clear()
            syncStateToCompose()
        }
    }

    fun dispose() {
        SwingUtilities.invokeLater {
            persistFrameNow()
            persistTimer?.stop()
            frame?.dispose()
            frame = null
            onSendCallback = null
            stagingState.clear()
        }
    }

    private fun syncStateToCompose() {
        stagedEntriesState = stagingState.stagedEntries
        isSendingState = stagingState.isSending
        isPinnedState = stagingState.isPinned
        destinationSubtitleState = stagingState.formatDestinations { count ->
            AppI18n.plural("n_destinations", count, count.toString())
        }
    }

    private fun ensureFrame() {
        if (frame != null) return

        val composePanel = ComposePanel().apply {
            background = java.awt.Color(DesktopTrayStyle.BACKGROUND_RGB)
            setContent {
                DropBoxContent(
                    stagedEntries = stagedEntriesState,
                    isSending = isSendingState,
                    isPinned = isPinnedState,
                    isDragOver = isDragOverState,
                    destinationText = destinationSubtitleState,
                    onTogglePin = {
                        val pinned = !isPinnedState
                        isPinnedState = pinned
                        stagingState.isPinned = pinned
                    },
                    onDragOverChange = { isDragOverState = it },
                    onFilesDropped = ::stageDroppedPaths,
                    onRemovePath = { path ->
                        stagingState.removePath(path)
                        syncStateToCompose()
                    },
                    onCancel = {
                        if (isPinnedState) {
                            stagingState.clear()
                            syncStateToCompose()
                        } else {
                            close()
                        }
                    },
                    onSend = {
                        val ids = stagingState.targetDeviceIds
                        val paths = stagingState.stagedPaths
                        if (ids.isNotEmpty() && paths.isNotEmpty() && !isSendingState) {
                            isSendingState = true
                            stagingState.isSending = true
                            onSendCallback?.invoke(ids, paths)
                        }
                    }
                )
            }
        }

        frame = JFrame(AppI18n.t("drop_files")).apply {
            defaultCloseOperation = WindowConstants.HIDE_ON_CLOSE
            isAlwaysOnTop = true
            isResizable = true
            minimumSize = Dimension(
                DesktopDropBoxBoundsStore.MIN_WIDTH_PX,
                DesktopDropBoxBoundsStore.MIN_HEIGHT_PX
            )
            contentPane = composePanel
            addWindowListener(object : WindowAdapter() {
                override fun windowClosing(e: WindowEvent?) {
                    persistFrameNow()
                }
            })
            addComponentListener(object : ComponentAdapter() {
                override fun componentMoved(e: ComponentEvent?) = schedulePersist()
                override fun componentResized(e: ComponentEvent?) = schedulePersist()
            })
        }
    }

    private fun stageDroppedPaths(paths: List<String>) {
        scope.launch(Dispatchers.IO) {
            val entries = DesktopDropBoxStagingState.inspect(paths)
            scope.launch(Dispatchers.Swing) {
                stagingState.append(entries)
                syncStateToCompose()
            }
        }
    }

    private fun applySavedOrDefaultBounds(window: JFrame) {
        val saved = DesktopDropBoxBoundsStore.loadValidated()
        if (saved != null) {
            window.setBounds(saved.x, saved.y, saved.width, saved.height)
        } else {
            window.setSize(
                DesktopDropBoxBoundsStore.DEFAULT_WIDTH_PX,
                DesktopDropBoxBoundsStore.DEFAULT_HEIGHT_PX
            )
            window.setLocationRelativeTo(null)
        }
    }

    private fun schedulePersist() {
        persistTimer?.stop()
        persistTimer = Timer(300) { persistFrameNow() }.apply { isRepeats = false; start() }
    }

    private fun persistFrameNow() {
        val window = frame ?: return
        if (!window.isVisible) return
        val b = window.bounds
        DesktopDropBoxBoundsStore.persistPixels(b.x, b.y, b.width, b.height)
    }
}
