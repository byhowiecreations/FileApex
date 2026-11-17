package com.fileapex.ui.notes

import com.fileapex.presentation.BulletinFilter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesEmptyCopyTest {
    private val english = File("src/commonMain/composeResources/files/i18n/en.xml").readText()

    private fun hasKey(key: String) = english.contains("name=\"$key\"")

    @Test
    fun noNotesYetShowsTheHintAndNoAction() {
        val copy = notesEmptyCopy(isSearchEmpty = false, isFilteredEmpty = false, filter = BulletinFilter.All)
        assertEquals("no_notes_yet", copy.titleKey)
        assertEquals("notes_empty_hint", copy.hintKey)
        assertNull(copy.actionKey)
    }

    @Test
    fun anEmptySearchOffersToClearIt() {
        val copy = notesEmptyCopy(isSearchEmpty = true, isFilteredEmpty = true, filter = BulletinFilter.All)
        assertEquals("no_matching_notes", copy.titleKey)
        assertNull(copy.hintKey)
        assertEquals("clear_search", copy.actionKey)
    }

    @Test
    fun anEmptyFilterOffersToShowAll() {
        val copy = notesEmptyCopy(isSearchEmpty = false, isFilteredEmpty = true, filter = BulletinFilter.Pinned)
        assertEquals("no_filtered_pinned", copy.titleKey)
        assertNull(copy.hintKey)
        assertEquals("filter_all", copy.actionKey)
    }

    @Test
    fun aTagFilterPassesItsTagIntoTheTitle() {
        val copy = notesEmptyCopy(isSearchEmpty = false, isFilteredEmpty = true, filter = BulletinFilter.Tag("work"))
        assertEquals("no_filtered_tag", copy.titleKey)
        assertEquals("work", copy.titleArg)
    }

    @Test
    fun everyFilterMapsToAKeyThatExists() {
        val filters = listOf(
            BulletinFilter.All, BulletinFilter.Links, BulletinFilter.Images, BulletinFilter.Documents,
            BulletinFilter.Snippets, BulletinFilter.Files, BulletinFilter.Pinned, BulletinFilter.Todo, BulletinFilter.Tag("x")
        )
        filters.forEach { filter ->
            val copy = notesEmptyCopy(isSearchEmpty = false, isFilteredEmpty = true, filter = filter)
            assertTrue("${copy.titleKey} missing", hasKey(copy.titleKey))
            copy.actionKey?.let { assertTrue("$it missing", hasKey(it)) }
        }
        val none = notesEmptyCopy(isSearchEmpty = false, isFilteredEmpty = false, filter = BulletinFilter.All)
        assertTrue(hasKey(none.titleKey) && hasKey(none.hintKey!!))
    }
}
