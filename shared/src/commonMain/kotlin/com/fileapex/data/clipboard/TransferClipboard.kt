package com.fileapex.data.clipboard

import com.fileapex.domain.model.ClipboardPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide transfer clipboard (COPY on one device, PASTE on another).
 * Survives navigation between paired-device explorers within this process.
 * Supports one or more files from a multi-select COPY.
 */
object TransferClipboard {
    private val _payloads = MutableStateFlow<List<ClipboardPayload>>(emptyList())
    val payloads: StateFlow<List<ClipboardPayload>> = _payloads.asStateFlow()

    fun copy(payload: ClipboardPayload) {
        _payloads.value = listOf(payload)
    }

    fun copyAll(items: List<ClipboardPayload>) {
        require(items.isNotEmpty()) { "Clipboard copy requires at least one file" }
        _payloads.value = items.toList()
    }

    fun peek(): ClipboardPayload? = _payloads.value.firstOrNull()

    fun peekAll(): List<ClipboardPayload> = _payloads.value

    fun clear() {
        _payloads.value = emptyList()
    }

    fun hasContent(): Boolean = _payloads.value.isNotEmpty()

    fun label(): String? {
        val items = _payloads.value
        if (items.isEmpty()) return null
        if (items.size == 1) return items.first().fileName
        return "${items.first().fileName} +${items.size - 1} more"
    }
}
