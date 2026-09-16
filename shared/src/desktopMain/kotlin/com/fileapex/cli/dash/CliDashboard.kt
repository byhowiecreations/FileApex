package com.fileapex.cli.dash

import com.fileapex.cli.dash.views.*
import com.fileapex.cli.ipc.CliActiveTransfer
import com.fileapex.cli.ipc.CliClusterState
import com.fileapex.cli.ipc.CliDeviceStatus
import com.fileapex.cli.ipc.CliQueueItem
import com.fileapex.cli.ipc.CliRemoteFile
import com.fileapex.update.FileApexAppVersion
import com.fileapex.util.PathUtils
import java.io.File
import java.text.DecimalFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

enum class DashboardView {
    MAIN,
    BATTERY,
    QUEUE,
    HELP,
    SEND_LOCAL_PICKER,
    SEND_TARGET_PICKER,
    RETRIEVE_DEVICE_PICKER,
    RETRIEVE_REMOTE_BROWSER
}

class CliDashboard(
    private val backend: CliClusterBackend,
    private val initialCwd: String = System.getProperty("user.dir") ?: "."
) {
    private var isRunning = true
    private var currentView = DashboardView.MAIN
    private var lastMessage: String? = null
    private var lastMessageIsError: Boolean = false

    private var terminalCols = 80
    private var terminalRows = 24

    private var clusterState: CliClusterState = CliClusterState(0, 0, 0, 0)
    private var lastStateFetchEpochMs = 0L
    @Volatile
    private var isShowingTransferCompletion = false

    // Local Picker State
    private var localCurrentDir: File = File(initialCwd).absoluteFile
    private var localEntries: List<File> = emptyList()
    private var localCursorIndex = 0
    private val localSelectedPaths = mutableSetOf<String>()

    // Target Picker State
    private var targetCursorIndex = 0
    private val targetSelectedDeviceIds = mutableSetOf<String>()

    // Remote Browser State
    private var remoteSelectedDevice: CliDeviceStatus? = null
    private var remoteCurrentPath: String = "/"
    private var remoteEntries: List<CliRemoteFile> = emptyList()
    private var remoteCursorIndex = 0

    fun run() {
        val originalErr = System.err
        System.setErr(java.io.PrintStream(java.io.OutputStream.nullOutputStream()))

        val initialSize = TerminalConsole.getTerminalSize()
        terminalCols = initialSize.first
        terminalRows = initialSize.second

        TerminalConsole.enableRawMode()
        TerminalConsole.hideCursor()
        TerminalConsole.clearScreen()

        try {
            runBlocking {
                refreshClusterState(force = true)
                var lastResizeCheck = 0L
                while (isRunning) {
                    val now = System.currentTimeMillis()
                    if (now - lastResizeCheck >= 1000L) {
                        lastResizeCheck = now
                        checkTerminalResize()
                    }

                    renderCurrentView()

                    // Poll key (non-blocking)
                    val key = TerminalConsole.readKey()
                    if (key != null) {
                        handleKey(key)
                    } else {
                        delay(35)
                    }

                    // Periodic background refresh in MAIN or BATTERY view
                    if (currentView == DashboardView.MAIN || currentView == DashboardView.BATTERY) {
                        val interval = if (clusterState.activeTransfersCount > 0) 80L else 350L
                        if (now - lastStateFetchEpochMs >= interval) {
                            refreshClusterState(force = false)
                        }
                    }
                }
            }
        } finally {
            System.setErr(originalErr)
            TerminalConsole.showCursor()
            TerminalConsole.restoreTerminal()
            print("\r\n")
            System.out.flush()
        }
    }

    private suspend fun refreshClusterState(force: Boolean) {
        val now = System.currentTimeMillis()
        val minInterval = if (clusterState.activeTransfersCount > 0) 60L else 300L
        if (force || now - lastStateFetchEpochMs >= minInterval) {
            lastStateFetchEpochMs = now
            val previousTransfersCount = clusterState.activeTransfersCount
            val newState = runCatching {
                backend.fetchClusterState(highlightBattery = (currentView == DashboardView.BATTERY), force = force)
            }.getOrDefault(clusterState)

            if (isShowingTransferCompletion) {
                clusterState = newState.copy(
                    activeTransfersCount = clusterState.activeTransfersCount,
                    activeTransfers = clusterState.activeTransfers,
                    activeTransferProgress = clusterState.activeTransferProgress,
                    activeTransferLabel = clusterState.activeTransferLabel
                )
            } else {
                if (previousTransfersCount > 0 && newState.activeTransfersCount == 0) {
                    TerminalConsole.clearScreen()
                }
                clusterState = newState
            }
        }
    }

    private fun checkTerminalResize() {
        val (cols, rows) = TerminalConsole.getTerminalSize()
        if (cols != terminalCols || rows != terminalRows) {
            terminalCols = cols
            terminalRows = rows
            TerminalConsole.clearScreen()
        }
    }

    private suspend fun handleKey(key: KeyEvent) {
        if (key.action == KeyAction.CTRL_C) {
            isRunning = false
            return
        }

        when (currentView) {
            DashboardView.MAIN -> handleMainKey(key)
            DashboardView.BATTERY -> handleBatteryKey(key)
            DashboardView.QUEUE -> handleQueueKey(key)
            DashboardView.HELP -> handleHelpKey(key)
            DashboardView.SEND_LOCAL_PICKER -> handleSendLocalPickerKey(key)
            DashboardView.SEND_TARGET_PICKER -> handleSendTargetPickerKey(key)
            DashboardView.RETRIEVE_DEVICE_PICKER -> handleRetrieveDevicePickerKey(key)
            DashboardView.RETRIEVE_REMOTE_BROWSER -> handleRetrieveRemoteBrowserKey(key)
        }
    }

    private suspend fun handleMainKey(key: KeyEvent) {
        if (key.action == KeyAction.ESCAPE) {
            isRunning = false
            return
        }

        if (key.action == KeyAction.CHAR) {
            when (key.char.lowercaseChar()) {
                'l' -> {
                    lastMessage = "Refreshed cluster status."
                    lastMessageIsError = false
                    refreshClusterState(force = true)
                    TerminalConsole.clearScreen()
                }
                's' -> {
                    lastMessage = null
                    openLocalPicker()
                }
                'r' -> {
                    lastMessage = null
                    openRetrieveDevicePicker()
                }
                'c' -> {
                    lastMessage = null
                    handleClipboardAction()
                }
                'b' -> {
                    lastMessage = null
                    currentView = DashboardView.BATTERY
                    refreshClusterState(force = true)
                    TerminalConsole.clearScreen()
                }
                'q' -> {
                    lastMessage = null
                    currentView = DashboardView.QUEUE
                    TerminalConsole.clearScreen()
                }
                'h' -> {
                    lastMessage = null
                    currentView = DashboardView.HELP
                    TerminalConsole.clearScreen()
                }
            }
        }
    }

    private fun handleBatteryKey(key: KeyEvent) {
        if (key.action == KeyAction.ESCAPE || (key.action == KeyAction.CHAR && (key.char.lowercaseChar() == 'b' || key.char.lowercaseChar() == 'l'))) {
            currentView = DashboardView.MAIN
            TerminalConsole.clearScreen()
        }
    }

    private suspend fun handleQueueKey(key: KeyEvent) {
        if (key.action == KeyAction.ESCAPE || (key.action == KeyAction.CHAR && key.char.lowercaseChar() == 'l')) {
            currentView = DashboardView.MAIN
            TerminalConsole.clearScreen()
            return
        }

        if (key.action == KeyAction.CHAR && (key.char.lowercaseChar() == 'r' || key.char.lowercaseChar() == 'd')) {
            val spec = TerminalConsole.readLinePrompt("\r\nEnter queue item # or range to remove (e.g. 1 or 2-4): ").trim()
            if (spec.isNotBlank()) {
                val queue = backend.getQueue()
                val indices = parseIndexRange(spec, queue.size)
                if (indices.isNotEmpty()) {
                    val res = backend.removeFromQueue(indices)
                    lastMessage = res.message
                    lastMessageIsError = !res.success
                } else {
                    lastMessage = "Invalid index/range: '$spec'"
                    lastMessageIsError = true
                }
            }
            TerminalConsole.clearScreen()
        }
    }

    private fun handleHelpKey(key: KeyEvent) {
        currentView = DashboardView.MAIN
        TerminalConsole.clearScreen()
    }

    // --- Local Picker (Send) ---
    private fun openLocalPicker() {
        localSelectedPaths.clear()
        localCursorIndex = 0
        updateLocalEntries()
        currentView = DashboardView.SEND_LOCAL_PICKER
        TerminalConsole.clearScreen()
    }

    private fun updateLocalEntries() {
        val files = localCurrentDir.listFiles()?.toList().orEmpty()
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        localEntries = files
        localCursorIndex = localCursorIndex.coerceIn(0, (localEntries.size).coerceAtLeast(0))
    }

    private suspend fun handleSendLocalPickerKey(key: KeyEvent) {
        val totalRows = localEntries.size + 1 // +1 for ".."
        when (key.action) {
            KeyAction.ESCAPE -> {
                currentView = DashboardView.MAIN
                TerminalConsole.clearScreen()
            }
            KeyAction.UP -> {
                localCursorIndex = (localCursorIndex - 1 + totalRows) % totalRows
            }
            KeyAction.DOWN -> {
                localCursorIndex = (localCursorIndex + 1) % totalRows
            }
            KeyAction.SPACE -> {
                if (localCursorIndex > 0) {
                    val file = localEntries[localCursorIndex - 1]
                    val path = file.absolutePath
                    if (localSelectedPaths.contains(path)) {
                        localSelectedPaths.remove(path)
                    } else {
                        localSelectedPaths.add(path)
                    }
                }
            }
            KeyAction.ENTER -> {
                if (localCursorIndex == 0) {
                    val parent = localCurrentDir.parentFile
                    if (parent != null && parent.exists()) {
                        localCurrentDir = parent
                        localCursorIndex = 0
                        updateLocalEntries()
                        TerminalConsole.clearScreen()
                    }
                } else {
                    val file = localEntries[localCursorIndex - 1]
                    if (file.isDirectory) {
                        localCurrentDir = file
                        localCursorIndex = 0
                        updateLocalEntries()
                        TerminalConsole.clearScreen()
                    }
                }
            }
            KeyAction.CHAR -> {
                when (key.char.lowercaseChar()) {
                    'k' -> localCursorIndex = (localCursorIndex - 1 + totalRows) % totalRows
                    'j' -> localCursorIndex = (localCursorIndex + 1) % totalRows
                    'a' -> {
                        val allPaths = localEntries.map { it.absolutePath }
                        if (localSelectedPaths.containsAll(allPaths)) {
                            localSelectedPaths.removeAll(allPaths.toSet())
                        } else {
                            localSelectedPaths.addAll(allPaths)
                        }
                    }
                    'p' -> {
                        val manual = TerminalConsole.readLinePrompt("\r\nEnter path to file or folder: ").trim()
                        if (manual.isNotBlank()) {
                            val f = File(manual).let { if (it.isAbsolute) it else File(localCurrentDir, manual) }
                            if (f.exists()) {
                                localSelectedPaths.add(f.absolutePath)
                                lastMessage = "Added path: ${f.name}"
                            } else {
                                lastMessage = "Path does not exist: $manual"
                                lastMessageIsError = true
                            }
                        }
                        TerminalConsole.clearScreen()
                    }
                    'c' -> {
                        if (localSelectedPaths.isEmpty() && localCursorIndex > 0) {
                            localSelectedPaths.add(localEntries[localCursorIndex - 1].absolutePath)
                        }
                        if (localSelectedPaths.isNotEmpty()) {
                            openSendTargetPicker()
                        } else {
                            lastMessage = "No files selected. Use [Space] to select."
                            lastMessageIsError = true
                        }
                    }
                }
            }
            else -> {}
        }
    }

    private fun openSendTargetPicker() {
        targetSelectedDeviceIds.clear()
        targetCursorIndex = 0
        currentView = DashboardView.SEND_TARGET_PICKER
        TerminalConsole.clearScreen()
    }

    private suspend fun handleSendTargetPickerKey(key: KeyEvent) {
        val devices = clusterState.devices
        if (devices.isEmpty()) {
            currentView = DashboardView.MAIN
            TerminalConsole.clearScreen()
            return
        }

        when (key.action) {
            KeyAction.ESCAPE -> {
                currentView = DashboardView.SEND_LOCAL_PICKER
                TerminalConsole.clearScreen()
            }
            KeyAction.UP -> {
                targetCursorIndex = (targetCursorIndex - 1 + devices.size) % devices.size
            }
            KeyAction.DOWN -> {
                targetCursorIndex = (targetCursorIndex + 1) % devices.size
            }
            KeyAction.SPACE -> {
                val dev = devices[targetCursorIndex]
                if (targetSelectedDeviceIds.contains(dev.deviceId)) {
                    targetSelectedDeviceIds.remove(dev.deviceId)
                } else {
                    targetSelectedDeviceIds.add(dev.deviceId)
                }
            }
            KeyAction.ENTER -> {
                if (targetSelectedDeviceIds.isEmpty()) {
                    targetSelectedDeviceIds.add(devices[targetCursorIndex].deviceId)
                }
                val paths = localSelectedPaths.toList()
                val targetIds = targetSelectedDeviceIds.toList()
                localSelectedPaths.clear()
                targetSelectedDeviceIds.clear()

                val firstFileName = paths.firstOrNull()?.let { File(it).name }.orEmpty()
                val targetNames = devices.filter { it.deviceId in targetIds }.map { it.deviceName }
                val targetSummary = if (targetNames.size <= 2) targetNames.joinToString(", ") else "${targetNames.size} devices"
                val sendLabel = if (paths.size > 1) "Sending ${paths.size} items -> $targetSummary" else "Sending $firstFileName -> $targetSummary"
                val immediateTransfers = targetIds.map { tId ->
                    val dName = devices.firstOrNull { it.deviceId == tId }?.deviceName ?: "peer"
                    CliActiveTransfer(
                        deviceId = tId,
                        deviceName = dName,
                        fileName = firstFileName,
                        progress = 0f,
                        speed = "",
                        eta = "",
                        bytesFormatted = ""
                    )
                }
                clusterState = clusterState.copy(
                    activeTransfersCount = immediateTransfers.size.coerceAtLeast(clusterState.activeTransfersCount),
                    activeTransfers = immediateTransfers,
                    activeTransferProgress = 0f,
                    activeTransferLabel = sendLabel
                )
                lastMessage = "Preparing transfer: $firstFileName -> $targetSummary..."
                lastMessageIsError = false
                currentView = DashboardView.MAIN
                TerminalConsole.clearScreen()

                CoroutineScope(Dispatchers.IO).launch {
                    val res = backend.sendLocalPaths(paths, targetIds)
                    lastMessage = res.message
                    lastMessageIsError = !res.success

                    if (res.success) {
                        isShowingTransferCompletion = true
                        val completedList = targetIds.map { tId ->
                            val dName = devices.firstOrNull { it.deviceId == tId }?.deviceName ?: "peer"
                            CliActiveTransfer(
                                deviceId = tId,
                                deviceName = dName,
                                fileName = firstFileName,
                                progress = 1.0f,
                                speed = "Completed",
                                eta = "",
                                bytesFormatted = ""
                            )
                        }
                        clusterState = clusterState.copy(
                            activeTransfersCount = completedList.size,
                            activeTransfers = completedList,
                            activeTransferProgress = 1.0f,
                            activeTransferLabel = res.message
                        )
                        delay(2000)
                        isShowingTransferCompletion = false
                    }

                    clusterState = clusterState.copy(
                        activeTransfersCount = 0,
                        activeTransfers = emptyList(),
                        activeTransferProgress = 0f,
                        activeTransferLabel = ""
                    )
                    TerminalConsole.clearScreen()
                    refreshClusterState(force = true)
                }
            }
            KeyAction.CHAR -> {
                when (key.char.lowercaseChar()) {
                    'k' -> targetCursorIndex = (targetCursorIndex - 1 + devices.size) % devices.size
                    'j' -> targetCursorIndex = (targetCursorIndex + 1) % devices.size
                }
            }
            else -> {}
        }
    }

    // --- Remote Browser (Retrieve) ---
    private fun openRetrieveDevicePicker() {
        targetCursorIndex = 0
        currentView = DashboardView.RETRIEVE_DEVICE_PICKER
        TerminalConsole.clearScreen()
    }

    private suspend fun handleRetrieveDevicePickerKey(key: KeyEvent) {
        val onlineDevices = clusterState.devices.filter { it.status == "ONLINE" || it.status == "CELLULAR" }
        if (onlineDevices.isEmpty()) {
            lastMessage = "No online devices available to browse."
            lastMessageIsError = true
            currentView = DashboardView.MAIN
            TerminalConsole.clearScreen()
            return
        }

        when (key.action) {
            KeyAction.ESCAPE -> {
                currentView = DashboardView.MAIN
                TerminalConsole.clearScreen()
            }
            KeyAction.UP -> {
                targetCursorIndex = (targetCursorIndex - 1 + onlineDevices.size) % onlineDevices.size
            }
            KeyAction.DOWN -> {
                targetCursorIndex = (targetCursorIndex + 1) % onlineDevices.size
            }
            KeyAction.ENTER -> {
                val dev = onlineDevices[targetCursorIndex]
                remoteSelectedDevice = dev
                remoteCurrentPath = "/"
                remoteCursorIndex = 0
                loadRemoteDirectory(dev.deviceId, remoteCurrentPath)
                currentView = DashboardView.RETRIEVE_REMOTE_BROWSER
                TerminalConsole.clearScreen()
            }
            KeyAction.CHAR -> {
                when (key.char.lowercaseChar()) {
                    'k' -> targetCursorIndex = (targetCursorIndex - 1 + onlineDevices.size) % onlineDevices.size
                    'j' -> targetCursorIndex = (targetCursorIndex + 1) % onlineDevices.size
                }
            }
            else -> {}
        }
    }

    private suspend fun loadRemoteDirectory(deviceId: String, path: String) {
        val result = backend.listRemoteFiles(deviceId, path)
        remoteCurrentPath = result.currentPath
        remoteEntries = result.items
        remoteCursorIndex = 0
    }

    private suspend fun handleRetrieveRemoteBrowserKey(key: KeyEvent) {
        val dev = remoteSelectedDevice ?: return
        val totalRows = remoteEntries.size + 1 // +1 for ".."
        when (key.action) {
            KeyAction.ESCAPE -> {
                currentView = DashboardView.MAIN
                TerminalConsole.clearScreen()
            }
            KeyAction.UP -> {
                remoteCursorIndex = (remoteCursorIndex - 1 + totalRows) % totalRows
            }
            KeyAction.DOWN -> {
                remoteCursorIndex = (remoteCursorIndex + 1) % totalRows
            }
            KeyAction.ENTER -> {
                if (remoteCursorIndex == 0) {
                    val norm = remoteCurrentPath.replace('\\', '/').trimEnd('/')
                    val parent = if (norm.contains('/') && norm.lastIndexOf('/') > 0) {
                        norm.substringBeforeLast('/').ifBlank { "/" }
                    } else {
                        "/"
                    }
                    loadRemoteDirectory(dev.deviceId, parent)
                    TerminalConsole.clearScreen()
                } else {
                    val item = remoteEntries[remoteCursorIndex - 1]
                    if (item.isDirectory) {
                        loadRemoteDirectory(dev.deviceId, item.absolutePath)
                        TerminalConsole.clearScreen()
                    }
                }
            }
            KeyAction.CHAR -> {
                when (key.char.lowercaseChar()) {
                    'k' -> remoteCursorIndex = (remoteCursorIndex - 1 + totalRows) % totalRows
                    'j' -> remoteCursorIndex = (remoteCursorIndex + 1) % totalRows
                    'd' -> {
                        if (remoteCursorIndex > 0) {
                            val item = remoteEntries[remoteCursorIndex - 1]
                            val res = backend.retrieveRemoteItem(dev.deviceId, item.absolutePath, item.isDirectory)
                            lastMessage = res.message
                            lastMessageIsError = !res.success
                            currentView = DashboardView.MAIN
                            TerminalConsole.clearScreen()
                        }
                    }
                }
            }
            else -> {}
        }
    }

    // --- Clipboard Prompt ---
    private suspend fun handleClipboardAction() {
        val action = TerminalConsole.readLinePrompt("\r\nAction? [1] Send current clipboard to peer [2] Retrieve clipboard from peer: ").trim()
        if (action != "1" && action != "2") {
            lastMessage = "Clipboard action cancelled."
            lastMessageIsError = false
            TerminalConsole.clearScreen()
            return
        }

        val devices = clusterState.devices
        if (devices.isEmpty()) {
            lastMessage = "No paired devices available."
            lastMessageIsError = true
            TerminalConsole.clearScreen()
            return
        }

        println("\r\nTarget Devices:")
        devices.forEach { dev ->
            println("  [${dev.id}] ${dev.slugOrAlias} (${dev.deviceName}) - ${dev.status}")
        }
        val targetInput = TerminalConsole.readLinePrompt("Select target device #: ").trim()
        val targetId = targetInput.toIntOrNull()
        val selectedDev = devices.firstOrNull { it.id == targetId }
        if (selectedDev == null) {
            lastMessage = "Invalid device number."
            lastMessageIsError = true
            TerminalConsole.clearScreen()
            return
        }

        if (action == "1") {
            val res = backend.sendClipboard(selectedDev.deviceId)
            lastMessage = res.message
            lastMessageIsError = !res.success
        } else {
            val res = backend.pullClipboard(selectedDev.deviceId)
            if (res.content != null) {
                lastMessage = "Retrieved clipboard (${res.content.length} chars) from ${selectedDev.slugOrAlias}."
                lastMessageIsError = false
            } else {
                lastMessage = res.error ?: "Clipboard was empty."
                lastMessageIsError = true
            }
        }
        TerminalConsole.clearScreen()
    }

    // --- Rendering Logic ---

    private fun renderCurrentView() {
        val sb = StringBuilder()
        sb.append("\u001b[H") // move cursor to 1,1

        when (currentView) {
            DashboardView.MAIN -> renderMainClusterView(sb, terminalCols, terminalRows, clusterState, lastMessage, lastMessageIsError)
            DashboardView.BATTERY -> renderBatteryMetricsView(sb, terminalCols, clusterState)
            DashboardView.QUEUE -> {
                val queue = runBlocking { backend.getQueue() }
                renderQueueView(sb, terminalCols, clusterState, queue, lastMessage, lastMessageIsError)
            }
            DashboardView.HELP -> renderHelpView(sb, terminalCols, clusterState)
            DashboardView.SEND_LOCAL_PICKER -> renderSendLocalPickerView(
                sb, terminalCols, localCurrentDir, localEntries, localCursorIndex, localSelectedPaths, lastMessage, lastMessageIsError
            )
            DashboardView.SEND_TARGET_PICKER -> renderSendTargetPickerView(
                sb, terminalCols, clusterState, targetCursorIndex, targetSelectedDeviceIds
            )
            DashboardView.RETRIEVE_DEVICE_PICKER -> renderRetrieveDevicePickerView(
                sb, terminalCols, clusterState, targetCursorIndex
            )
            DashboardView.RETRIEVE_REMOTE_BROWSER -> {
                val dev = remoteSelectedDevice
                if (dev != null) {
                    renderRetrieveRemoteBrowserView(
                        sb, terminalCols, dev, remoteCurrentPath, remoteEntries, remoteCursorIndex
                    )
                }
            }
        }

        sb.append("\u001b[J") // Erase from cursor to end of screen (prevents phantom duplicated footers/messages)
        print(sb.toString())
        System.out.flush()
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
            val df = DecimalFormat("#,##0.#")
            return "${df.format(bytes / Math.pow(1024.0, digitGroups.toDouble()))} ${units[digitGroups]}"
        }

        fun parseIndexRange(spec: String, maxIndex: Int): List<Int> {
            if (spec.contains('-')) {
                val parts = spec.split('-')
                if (parts.size == 2) {
                    val start = parts[0].toIntOrNull() ?: return emptyList()
                    val end = parts[1].toIntOrNull() ?: return emptyList()
                    if (start in 1..maxIndex && end in 1..maxIndex && start <= end) {
                        return (start..end).toList()
                    }
                }
                return emptyList()
            }
            val single = spec.toIntOrNull() ?: return emptyList()
            return if (single in 1..maxIndex) listOf(single) else emptyList()
        }
    }
}
