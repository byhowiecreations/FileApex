package com.fileapex.ui.adaptive.simple

import com.fileapex.i18n.AppI18n
import com.fileapex.ui.adaptive.SimpleRailEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class SimpleBottomBarTabsTest {
    @Test
    fun theCompactBarCarriesBulletinBoardOnEveryHost() {
        assertEquals(
            listOf(SimpleRailEntry.LocalFiles, SimpleRailEntry.Browse, SimpleRailEntry.BulletinBoard, SimpleRailEntry.Settings),
            SimpleBottomBarLayoutPolicy.tabs()
        )
    }

    @Test
    fun theBarTabsAreAllRailEntriesSoBothNavigationsAgree() {
        val rail = SimpleRailCatalog.primaryEntries()
        assertEquals(emptyList<SimpleRailEntry>(), SimpleBottomBarLayoutPolicy.tabs().filterNot { it in rail })
    }

    @Test
    fun theRemoteDeviceEntryIsCalledRemoteFilesToMatchLocalFiles() {
        assertEquals("local_files", SimpleRailCatalog.labelKey(SimpleRailEntry.LocalFiles))
        assertEquals("remote_files", SimpleRailCatalog.labelKey(SimpleRailEntry.Browse))
        assertEquals("bulletin_board", SimpleRailCatalog.labelKey(SimpleRailEntry.BulletinBoard))
        assertEquals("Remote Files", AppI18n.t("remote_files"))
        assertEquals("Local Files", AppI18n.t("local_files"))
    }
}
