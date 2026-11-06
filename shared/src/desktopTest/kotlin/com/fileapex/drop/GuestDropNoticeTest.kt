package com.fileapex.drop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class GuestDropNoticeTest {
    private fun info(id: String, name: String = "$id.bin", downloading: Boolean = false) = DropInfo(id, name, 10L, 1L, downloading)

    @Test fun noLinkMeansNoNotice() {
        assertEquals(GuestDropNotice.Off, guestDropNoticeFor(emptyList()))
    }

    @Test fun oneLinkNamesTheFile() {
        assertEquals(GuestDropNotice.One("a.bin"), guestDropNoticeFor(listOf(info("a"))))
    }

    @Test fun severalLinksAreCounted() {
        assertEquals(GuestDropNotice.Many(3), guestDropNoticeFor(listOf(info("a"), info("b"), info("c"))))
    }

    @Test fun theWatcherTellsOnlyAboutRealChanges() {
        val active = MutableStateFlow<List<DropInfo>>(emptyList())
        val seen = mutableListOf<GuestDropNotice>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            GuestDropNoticeWatcher(scope, active) { seen += it }
            assertEquals(emptyList<GuestDropNotice>(), seen)
            active.value = listOf(info("a"))
            active.value = listOf(info("a", downloading = true))
            active.value = listOf(info("a"), info("b"))
            active.value = listOf(info("b"))
            active.value = emptyList()
            active.value = emptyList()
            assertEquals(
                listOf(GuestDropNotice.One("a.bin"), GuestDropNotice.Many(2), GuestDropNotice.One("b.bin"), GuestDropNotice.Off),
                seen
            )
        } finally {
            scope.cancel()
        }
    }

    @Test fun theNoticeStringsExistInEveryLanguage() {
        val master = java.io.File("src/commonMain/composeResources/files/i18n")
        listOf("en", "es", "zh-rCN").forEach { language ->
            val text = java.io.File(master, "$language.xml").readText()
            GuestDropNoticeStrings.keys.forEach { assert(text.contains("name=\"$it\"")) { "$language lacks $it" } }
        }
    }
}
