package com.fileapex.presentation

import androidx.lifecycle.ViewModel
import com.fileapex.data.note.NoteRecord
import com.fileapex.data.settings.BulletinBoardStyle
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.platform.isWebUrl
import com.fileapex.platform.textContainsWebUrl
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface BulletinFilter {
    data object All : BulletinFilter
    data object Links : BulletinFilter
    data object Images : BulletinFilter
    data object Documents : BulletinFilter
    data object Snippets : BulletinFilter
    data object Files : BulletinFilter
    data object Pinned : BulletinFilter
    data class Tag(val tag: String) : BulletinFilter
}

sealed class NotesListRow {
    data class DayHeader(val dayKey: String, val label: String) : NotesListRow()
    data class Bubble(val note: NoteRecord) : NotesListRow()
}

private val BULLETIN_IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "webp", "gif", "bmp", "svg", "heic", "heif", "tiff", "avif"
)

private val BULLETIN_DOC_EXTENSIONS = setOf(
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv",
    "rtf", "epub", "json", "xml", "log", "html", "htm"
)

fun isBulletinImage(fileName: String?): Boolean {
    if (fileName.isNullOrBlank()) return false
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return ext in BULLETIN_IMAGE_EXTENSIONS
}

fun isBulletinDoc(fileName: String?): Boolean {
    if (fileName.isNullOrBlank()) return false
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return ext in BULLETIN_DOC_EXTENSIONS
}

fun isBulletinOtherFile(fileName: String?): Boolean {
    if (fileName.isNullOrBlank()) return false
    return !isBulletinImage(fileName) && !isBulletinDoc(fileName)
}

fun isBulletinSnippet(note: NoteRecord): Boolean {
    if (!note.attachmentFileName.isNullOrBlank()) return false
    val text = note.content.trim()
    if (text.isEmpty()) return false
    if (isWebUrl(text) || textContainsWebUrl(text)) return false
    return text.length <= 120
}

fun extractBulletinHashtags(text: String): List<String> {
    if (!text.contains('#')) return emptyList()
    val regex = Regex("""(?<!\w)#([a-zA-Z0-9_\-]+)""")
    return regex.findAll(text).map { it.groupValues[1].lowercase() }.distinct().toList()
}

data class NotesUiState(
    val visualRows: List<NotesListRow> = emptyList(),
    val rawNotes: List<NoteRecord> = emptyList(),
    val downloadingAttachmentIds: Set<String> = emptySet(),
    val bulletinBoardStyle: BulletinBoardStyle = BulletinBoardStyle.DEFAULT,
    val activeFilter: BulletinFilter = BulletinFilter.All,
    val hasLinks: Boolean = false,
    val hasImages: Boolean = false,
    val hasDocs: Boolean = false,
    val hasSnippets: Boolean = false,
    val hasOtherFiles: Boolean = false,
    val hasPinned: Boolean = false,
    val availableTags: List<String> = emptyList()
)

class NotesViewModel(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
) : ViewModel() {
    private val activeFilterFlow = MutableStateFlow<BulletinFilter>(BulletinFilter.All)
    private val activeFilterState = activeFilterFlow.asStateFlow()

    private val rawNotesFlow = FileApexServices.noteRepository.notes
    private val downloadingFlow = FileApexServices.noteRepository.downloadingAttachmentIds
    private val styleFlow = FileApexServices.settings.bulletinBoardStyle

    val uiState: StateFlow<NotesUiState> = combine(
        rawNotesFlow,
        downloadingFlow,
        styleFlow,
        activeFilterState
    ) { notes, downloadingIds, style, filter ->
        val sortedNotes = notes.sortedBy { it.epochMs }
        val hasLinks = sortedNotes.any { isWebUrl(it.content) || textContainsWebUrl(it.content) }
        val hasImages = sortedNotes.any { isBulletinImage(it.attachmentFileName) }
        val hasDocs = sortedNotes.any { isBulletinDoc(it.attachmentFileName) }
        val hasSnippets = sortedNotes.any { isBulletinSnippet(it) }
        val hasOtherFiles = sortedNotes.any { isBulletinOtherFile(it.attachmentFileName) }
        val hasPinned = sortedNotes.any { it.attachmentPinned }
        val tags = sortedNotes.flatMap { extractBulletinHashtags(it.content) }.distinct().sorted()

        val validFilter = when (filter) {
            BulletinFilter.All -> filter
            BulletinFilter.Links -> if (hasLinks) filter else BulletinFilter.All
            BulletinFilter.Images -> if (hasImages) filter else BulletinFilter.All
            BulletinFilter.Documents -> if (hasDocs) filter else BulletinFilter.All
            BulletinFilter.Snippets -> if (hasSnippets) filter else BulletinFilter.All
            BulletinFilter.Files -> if (hasOtherFiles) filter else BulletinFilter.All
            BulletinFilter.Pinned -> if (hasPinned) filter else BulletinFilter.All
            is BulletinFilter.Tag -> if (filter.tag in tags) filter else BulletinFilter.All
        }

        val filteredNotes = when (validFilter) {
            BulletinFilter.All -> sortedNotes
            BulletinFilter.Links -> sortedNotes.filter { isWebUrl(it.content) || textContainsWebUrl(it.content) }
            BulletinFilter.Images -> sortedNotes.filter { isBulletinImage(it.attachmentFileName) }
            BulletinFilter.Documents -> sortedNotes.filter { isBulletinDoc(it.attachmentFileName) }
            BulletinFilter.Snippets -> sortedNotes.filter { isBulletinSnippet(it) }
            BulletinFilter.Files -> sortedNotes.filter { isBulletinOtherFile(it.attachmentFileName) }
            BulletinFilter.Pinned -> sortedNotes.filter { it.attachmentPinned }
            is BulletinFilter.Tag -> {
                val target = validFilter.tag.lowercase()
                sortedNotes.filter { target in extractBulletinHashtags(it.content) }
            }
        }

        val rows = buildDayGroupedRows(filteredNotes)
        NotesUiState(
            visualRows = rows.asReversed(),
            rawNotes = sortedNotes,
            downloadingAttachmentIds = downloadingIds,
            bulletinBoardStyle = style,
            activeFilter = validFilter,
            hasLinks = hasLinks,
            hasImages = hasImages,
            hasDocs = hasDocs,
            hasSnippets = hasSnippets,
            hasOtherFiles = hasOtherFiles,
            hasPinned = hasPinned,
            availableTags = tags
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = NotesUiState()
    )

    fun setFilter(filter: BulletinFilter) {
        activeFilterFlow.value = filter
    }

    fun deleteNoteLocally(noteId: String) {
        scope.launch {
            FileApexServices.noteRepository.deleteNote(noteId)
        }
    }

    fun deleteNoteFromAllDevices(noteId: String, remotePurge: Boolean = false) {
        scope.launch {
            FileApexServices.noteRepository.deleteNoteFromAllDevices(noteId, remotePurge)
        }
    }

    fun setAttachmentPinned(noteId: String, pinned: Boolean) {
        scope.launch {
            FileApexServices.noteRepository.setAttachmentPinned(noteId, pinned)
        }
    }

    fun createOutgoingPlaceholder(fileName: String?, caption: String, path: String?): NoteRecord {
        return NoteRecord(
            noteId = "notes-outgoing-placeholder",
            sourceDeviceId = "",
            sourceDeviceName = AppI18n.t("this_device"),
            content = caption,
            epochMs = TimeUtils.now(),
            isMine = true,
            attachmentFileName = fileName,
            attachmentLocalPath = path
        )
    }

    private fun buildDayGroupedRows(notes: List<NoteRecord>): List<NotesListRow> {
        val rows = mutableListOf<NotesListRow>()
        var lastDay: String? = null
        for (note in notes) {
            val stamp = TimeUtils.noteListStamp(note.epochMs)
            if (stamp.dayKey != lastDay) {
                rows += NotesListRow.DayHeader(stamp.dayKey, stamp.dateHeader)
                lastDay = stamp.dayKey
            }
            rows += NotesListRow.Bubble(note)
        }
        return rows
    }
}
