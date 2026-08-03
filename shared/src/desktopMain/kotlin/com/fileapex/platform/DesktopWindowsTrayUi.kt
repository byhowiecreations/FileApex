package com.fileapex.platform

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Point
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants

/**
 * Windows "Drop Files" panel — Mac [DropBoxWindowManager] parity (drag/drop + Send).
 */
object DesktopWindowsDropBox {
    private val teal = Color(0x1B, 0x5E, 0x4B)
    private val tealSoft = Color(0x1B, 0x5E, 0x4B, 28)

    private var frame: JFrame? = null
    private var targetDeviceIds: List<String> = emptyList()
    private var stagedPaths: List<String> = emptyList()
    private var isSending = false
    private var onSend: ((deviceIds: List<String>, paths: List<String>) -> Unit)? = null
    private var persistTimer: Timer? = null

    private lateinit var statusLabel: JLabel
    private lateinit var destinationsLabel: JLabel
    private lateinit var sendButton: JButton
    private lateinit var dropPanel: JPanel

    fun show(
        deviceIds: List<String>,
        onSend: (deviceIds: List<String>, paths: List<String>) -> Unit
    ) {
        require(deviceIds.isNotEmpty())
        SwingUtilities.invokeLater {
            this.onSend = onSend
            targetDeviceIds = deviceIds
            stagedPaths = emptyList()
            isSending = false
            ensureFrame()
            destinationsLabel.text = "${deviceIds.size} destination(s)"
            refreshFileUi()
            val window = frame ?: return@invokeLater
            applySavedOrDefaultBounds(window)
            window.isVisible = true
            window.toFront()
            window.requestFocus()
        }
    }

    fun close() {
        SwingUtilities.invokeLater {
            persistFrameNow()
            frame?.isVisible = false
            stagedPaths = emptyList()
            isSending = false
            refreshFileUi()
        }
    }

    fun dispose() {
        SwingUtilities.invokeLater {
            persistFrameNow()
            persistTimer?.stop()
            frame?.dispose()
            frame = null
            onSend = null
            targetDeviceIds = emptyList()
            stagedPaths = emptyList()
            isSending = false
        }
    }

    private fun ensureFrame() {
        if (frame != null) return
        statusLabel = JLabel("Drag & drop files here", SwingConstants.CENTER).apply {
            font = font.deriveFont(Font.BOLD, 13f)
        }
        destinationsLabel = JLabel("1 destination(s)", SwingConstants.CENTER).apply {
            foreground = Color.GRAY
            font = font.deriveFont(Font.PLAIN, 11f)
        }
        sendButton = JButton("Send").apply {
            isVisible = false
            setUI(javax.swing.plaf.basic.BasicButtonUI())
            background = teal
            foreground = Color.WHITE
            isOpaque = true
            isContentAreaFilled = true
            isBorderPainted = false
            isFocusPainted = false
            addActionListener { submitSend() }
        }
        dropPanel = JPanel(GridBagLayout()).apply {
            background = Color.WHITE
            border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
            val inner = JPanel(GridBagLayout()).apply {
                isOpaque = false
                val c = GridBagConstraints().apply {
                    gridx = 0
                    fill = GridBagConstraints.HORIZONTAL
                    weightx = 1.0
                    insets = Insets(4, 0, 4, 0)
                }
                add(JLabel("⬇", SwingConstants.CENTER).apply {
                    font = font.deriveFont(28f)
                    foreground = teal
                }, c)
                c.gridy = 1
                add(statusLabel, c)
                c.gridy = 2
                add(destinationsLabel, c)
                c.gridy = 3
                c.insets = Insets(12, 0, 0, 0)
                add(sendButton, c)
            }
            add(inner)
            DropTarget(
                this,
                DnDConstants.ACTION_COPY,
                object : DropTargetAdapter() {
                    override fun dragEnter(dtde: DropTargetDragEvent) {
                        if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                            dtde.acceptDrag(DnDConstants.ACTION_COPY)
                            dropPanel.background = tealSoft
                        } else {
                            dtde.rejectDrag()
                        }
                    }

                    override fun dragExit(dte: DropTargetEvent) {
                        dropPanel.background = Color.WHITE
                    }

                    override fun drop(dtde: DropTargetDropEvent) {
                        dropPanel.background = Color.WHITE
                        dtde.acceptDrop(DnDConstants.ACTION_COPY)
                        val transfer = dtde.transferable
                        val paths = runCatching {
                            @Suppress("UNCHECKED_CAST")
                            val files = transfer.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                            files.mapNotNull { it.absolutePath.takeIf { path -> path.isNotBlank() } }
                        }.getOrDefault(emptyList())
                        if (paths.isNotEmpty()) {
                            stagedPaths = paths
                            refreshFileUi()
                        }
                        dtde.dropComplete(paths.isNotEmpty())
                    }
                },
                true
            )
        }

        frame = JFrame("Drop Files").apply {
            defaultCloseOperation = WindowConstants.HIDE_ON_CLOSE
            isAlwaysOnTop = true
            isResizable = true
            minimumSize = Dimension(
                DesktopDropBoxBoundsStore.MIN_WIDTH_PX,
                DesktopDropBoxBoundsStore.MIN_HEIGHT_PX
            )
            contentPane = dropPanel
            pack()
            preferredSize = Dimension(
                DesktopDropBoxBoundsStore.DEFAULT_WIDTH_PX,
                DesktopDropBoxBoundsStore.DEFAULT_HEIGHT_PX
            )
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

    private fun refreshFileUi() {
        if (!::statusLabel.isInitialized) return
        statusLabel.text = if (stagedPaths.isEmpty()) {
            "Drag & drop files here"
        } else {
            "${stagedPaths.size} file(s) ready"
        }
        sendButton.isVisible = stagedPaths.isNotEmpty()
        sendButton.text = if (isSending) "Sending" else "Send"
        sendButton.isEnabled = !isSending && stagedPaths.isNotEmpty()
        dropPanel.revalidate()
        dropPanel.repaint()
    }

    private fun submitSend() {
        if (isSending) return
        if (targetDeviceIds.isEmpty()) return
        if (stagedPaths.isEmpty()) {
            DesktopAwtTrayCoordinator.showBalloon("Drop one or more files first")
            return
        }
        isSending = true
        refreshFileUi()
        val ids = targetDeviceIds.toList()
        val paths = stagedPaths.toList()
        onSend?.invoke(ids, paths)
    }

    fun onSendFinished() {
        SwingUtilities.invokeLater {
            isSending = false
            close()
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
        persistTimer = Timer(400) { persistFrameNow() }.apply {
            isRepeats = false
            start()
        }
    }

    private fun persistFrameNow() {
        val window = frame ?: return
        if (!window.isDisplayable) return
        DesktopDropBoxBoundsStore.persistPixels(
            x = window.x,
            y = window.y,
            width = window.width,
            height = window.height
        )
    }
}

/**
 * Floating tray device popover — Mac [TrayMenuView] parity (Ctrl multi-select + Drop Files).
 */
internal class DesktopWindowsTrayPopover(
    private val onLaunchApp: () -> Unit,
    private val onQuitApp: () -> Unit,
    private val onOpenDropBox: (deviceIds: List<String>) -> Unit,
    private val onRefreshDevices: () -> Unit
) {
    private val teal = Color(0x1B, 0x5E, 0x4B)
    // Opaque colors only — alpha backgrounds corrupt Swing text on hover.
    private val selectedBg = Color(0xDC, 0xEB, 0xE5)
    private val hoverBg = Color(0xF2, 0xF2, 0xF2)
    private val rowWidth = 280

    private val selectedDeviceIds = linkedSetOf<String>()
    private var roster: List<DesktopTrayDeviceSnapshot> = emptyList()
    private var suppressFocusDismiss = false

    private val window = JFrame().apply {
        isUndecorated = true
        isAlwaysOnTop = true
        // UTILITY + focusable so click-away fires windowLostFocus (POPUP often never focuses).
        type = java.awt.Window.Type.UTILITY
        focusableWindowState = true
        defaultCloseOperation = WindowConstants.HIDE_ON_CLOSE
        background = Color.WHITE
        addWindowFocusListener(
            object : java.awt.event.WindowAdapter() {
                override fun windowLostFocus(e: java.awt.event.WindowEvent?) {
                    if (suppressFocusDismiss) return
                    val frame = this@apply
                    // Defer so Ctrl-click / button presses inside the panel still complete.
                    SwingUtilities.invokeLater {
                        if (!frame.isFocused && frame.isVisible && !suppressFocusDismiss) {
                            hide()
                        }
                    }
                }
            }
        )
    }

    private val deviceListPanel = JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        background = Color.WHITE
        isOpaque = true
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private val hintLabel = JLabel("Ctrl-click to multi-select · click to send").apply {
        foreground = Color.GRAY
        font = font.deriveFont(Font.PLAIN, 11f)
        border = BorderFactory.createEmptyBorder(8, 4, 0, 4)
    }

    private val sendMultiButton = JButton().apply {
        isVisible = false
        setUI(javax.swing.plaf.basic.BasicButtonUI())
        background = teal
        foreground = Color.WHITE
        isOpaque = true
        isContentAreaFilled = true
        isBorderPainted = false
        isFocusPainted = false
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, 32)
        addActionListener { openDropBoxForSelected() }
    }

    private val root = JPanel(BorderLayout(0, 8)).apply {
        background = Color.WHITE
        isOpaque = true
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color(0xD0, 0xD0, 0xD0)),
            BorderFactory.createEmptyBorder(12, 12, 12, 12)
        )
        add(buildHeader(), BorderLayout.NORTH)
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = true
                background = Color.WHITE
                add(deviceListPanel, BorderLayout.NORTH)
                add(
                    JPanel().apply {
                        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                        isOpaque = true
                        background = Color.WHITE
                        alignmentX = Component.LEFT_ALIGNMENT
                        add(sendMultiButton)
                        add(hintLabel)
                    },
                    BorderLayout.SOUTH
                )
            },
            BorderLayout.CENTER
        )
    }

    init {
        window.contentPane = root
        window.pack()
    }

    val isShowing: Boolean
        get() = window.isVisible

    fun toggleOrShow(screenAnchor: Point, devices: List<DesktopTrayDeviceSnapshot>) {
        if (window.isVisible) {
            hide()
            return
        }
        show(screenAnchor, devices)
    }

    fun show(screenAnchor: Point, devices: List<DesktopTrayDeviceSnapshot>) {
        onRefreshDevices()
        roster = devices
        selectedDeviceIds.clear()
        rebuildDeviceRows()
        updateFooter()
        window.pack()
        positionAbove(screenAnchor)
        suppressFocusDismiss = true
        window.isVisible = true
        window.toFront()
        window.requestFocus()
        // Allow focus settle before enabling click-away dismiss.
        SwingUtilities.invokeLater {
            suppressFocusDismiss = false
            if (window.isVisible) {
                window.toFront()
                window.requestFocus()
            }
        }
    }

    fun hide() {
        suppressFocusDismiss = true
        window.isVisible = false
        selectedDeviceIds.clear()
        suppressFocusDismiss = false
    }

    fun dispose() {
        hide()
        window.dispose()
    }

    fun updateDevices(devices: List<DesktopTrayDeviceSnapshot>) {
        if (devices == roster) return
        roster = devices
        if (!window.isVisible) return
        selectedDeviceIds.retainAll(devices.map { it.id }.toSet())
        rebuildDeviceRows()
        updateFooter()
        window.pack()
    }

    private fun buildHeader(): JPanel {
        val title = JLabel("Devices").apply {
            font = font.deriveFont(Font.BOLD, 14f)
        }
        val quit = iconButton("X", "Quit Application") { onQuitApp() }
        val launch = iconButton("↗", "Launch full app") { onLaunchApp() }
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = Color.WHITE
            add(title, BorderLayout.WEST)
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                    isOpaque = true
                    background = Color.WHITE
                    add(quit)
                    add(launch)
                },
                BorderLayout.EAST
            )
            border = BorderFactory.createEmptyBorder(0, 0, 6, 0)
        }
    }

    private fun iconButton(label: String, tooltip: String, onClick: () -> Unit): JButton =
        JButton(label).apply {
            toolTipText = tooltip
            margin = Insets(2, 8, 2, 8)
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener {
                hide()
                onClick()
            }
        }

    private fun rebuildDeviceRows() {
        deviceListPanel.removeAll()
        if (roster.isEmpty()) {
            deviceListPanel.add(
                JLabel("No paired devices").apply {
                    foreground = Color.GRAY
                    font = font.deriveFont(Font.PLAIN, 12f)
                    border = BorderFactory.createEmptyBorder(8, 4, 8, 4)
                    alignmentX = Component.LEFT_ALIGNMENT
                }
            )
        } else {
            roster.forEach { device ->
                deviceListPanel.add(deviceRow(device))
            }
        }
        deviceListPanel.revalidate()
        deviceListPanel.repaint()
    }

    private fun deviceRow(device: DesktopTrayDeviceSnapshot): JPanel {
        val selected = device.id in selectedDeviceIds
        val statusColor = if (device.isOnline) Color.GRAY else Color(0xC6, 0x28, 0x28)
        val nameLabel = JLabel(device.name).apply {
            font = font.deriveFont(Font.BOLD, 12f)
        }
        val statusLabel = JLabel(if (device.isOnline) "Online" else "Offline").apply {
            foreground = statusColor
            font = font.deriveFont(Font.PLAIN, 11f)
        }
        val check = JLabel(if (selected) "✓" else " ").apply {
            foreground = teal
            preferredSize = Dimension(16, 16)
            horizontalAlignment = SwingConstants.CENTER
        }
        val icon = JLabel(if (device.isOnline) "●" else "○").apply {
            foreground = if (device.isOnline) Color(0x2E, 0x7D, 0x32) else Color.GRAY
            preferredSize = Dimension(18, 18)
            horizontalAlignment = SwingConstants.CENTER
        }
        val textCol = JPanel(GridBagLayout()).apply {
            isOpaque = false
            val c = GridBagConstraints().apply {
                gridx = 0
                anchor = GridBagConstraints.WEST
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
            }
            add(nameLabel, c)
            c.gridy = 1
            add(statusLabel, c)
        }
        return JPanel(BorderLayout(8, 0)).apply {
            isOpaque = true
            background = if (selected) selectedBg else Color.WHITE
            border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
            preferredSize = Dimension(rowWidth, 48)
            maximumSize = Dimension(rowWidth, 48)
            minimumSize = Dimension(rowWidth, 48)
            alignmentX = Component.LEFT_ALIGNMENT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(icon, BorderLayout.WEST)
            add(textCol, BorderLayout.CENTER)
            add(check, BorderLayout.EAST)
            // Forward child mouse events so hover isn't lost when crossing labels.
            val row = this
            val hoverHandler = object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    applyRowBackground(row, device.id, hovered = true)
                }

                override fun mouseExited(e: MouseEvent) {
                    // Point is relative to the source component — convert into row space.
                    val inRow = SwingUtilities.convertPoint(e.component, e.point, row)
                    if (row.contains(inRow)) return
                    applyRowBackground(row, device.id, hovered = false)
                }

                override fun mouseClicked(e: MouseEvent) {
                    handleDeviceClick(device, e.isControlDown)
                }
            }
            addMouseListener(hoverHandler)
            icon.addMouseListener(hoverHandler)
            nameLabel.addMouseListener(hoverHandler)
            statusLabel.addMouseListener(hoverHandler)
            check.addMouseListener(hoverHandler)
            textCol.addMouseListener(hoverHandler)
        }
    }

    private fun applyRowBackground(row: JPanel, deviceId: String, hovered: Boolean) {
        row.background = when {
            deviceId in selectedDeviceIds -> selectedBg
            hovered -> hoverBg
            else -> Color.WHITE
        }
        row.repaint()
    }

    private fun handleDeviceClick(device: DesktopTrayDeviceSnapshot, ctrlPressed: Boolean) {
        if (ctrlPressed) {
            suppressFocusDismiss = true
            if (device.id in selectedDeviceIds) {
                selectedDeviceIds.remove(device.id)
            } else {
                selectedDeviceIds.add(device.id)
            }
            rebuildDeviceRows()
            updateFooter()
            window.pack()
            window.requestFocus()
            SwingUtilities.invokeLater { suppressFocusDismiss = false }
        } else {
            selectedDeviceIds.clear()
            selectedDeviceIds.add(device.id)
            openDropBoxForSelected()
        }
    }

    private fun updateFooter() {
        val count = selectedDeviceIds.size
        if (count > 1) {
            sendMultiButton.text = "Send to $count Devices"
            sendMultiButton.isVisible = true
            hintLabel.isVisible = false
        } else {
            sendMultiButton.isVisible = false
            hintLabel.isVisible = true
        }
    }

    private fun openDropBoxForSelected() {
        val ids = selectedDeviceIds.toList()
        if (ids.isEmpty()) return
        suppressFocusDismiss = true
        hide()
        onOpenDropBox(ids)
        SwingUtilities.invokeLater { suppressFocusDismiss = false }
    }

    private fun positionAbove(anchor: Point) {
        val size = window.preferredSize
        val screen = Toolkit.getDefaultToolkit().screenSize
        var x = anchor.x - size.width / 2
        var y = anchor.y - size.height - 8
        x = x.coerceIn(8, (screen.width - size.width - 8).coerceAtLeast(8))
        if (y < 8) y = (anchor.y + 8).coerceAtMost(screen.height - size.height - 8)
        window.setLocation(x, y)
        window.size = size
    }
}
