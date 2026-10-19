package com.fileapex.iroh

import com.fileapex.util.AddressRedaction
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object IrohLog {
    private const val MAX_LINES = 60
    private val stamp = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val lines = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = lines.asStateFlow()

    fun add(message: String) {
        val line = "${LocalTime.now().format(stamp)} ${AddressRedaction.redact(message)}"
        println("Iroh: $line")
        synchronized(this) {
            lines.value = (lines.value + line).takeLast(MAX_LINES)
        }
    }

    /** Maps a full endpoint id to a device name, or null when unknown. Set by the runtime. */
    @Volatile
    var labeler: (String) -> String? = { null }

    fun short(id: String): String {
        val key = id.take(8)
        val name = labeler(id)
        return if (name.isNullOrBlank()) key else "$name ($key)"
    }
}
