package com.fileapex.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRootScrollTest {
    @Test
    fun theRootKeepsItsScrollPositionWhileASubPageIsOpen() {
        val holder = SettingsNavigationState()
        val scene = ImageComposeScene(width = 300, height = 400, density = Density(1f)) {
            if (holder.page.value == SettingsPage.Root) {
                Column(Modifier.fillMaxSize().verticalScroll(holder.rootScroll)) {
                    repeat(40) { Box(Modifier.height(50.dp)) }
                }
            } else {
                Box(Modifier.fillMaxSize())
            }
        }
        fun frames() { scene.render(0L); scene.render(16_000_000L); scene.render(32_000_000L).close() }
        frames()
        runBlocking { holder.rootScroll.scrollTo(600) }
        frames()
        assertEquals(600, holder.rootScroll.value)

        holder.page.value = SettingsPage.Notifications
        frames()
        holder.page.value = SettingsPage.Root
        frames()
        scene.close()

        assertEquals(600, holder.rootScroll.value)
    }

    @Test
    fun theRootPageScrollsWithTheHolderStateAndNotItsOwn() {
        val screen = File("src/commonMain/kotlin/com/fileapex/ui/SettingsScreen.kt").readText()
        assertTrue("the root page must scroll with the held state", screen.contains("verticalScroll(scrollState)"))
        assertFalse("a scroll state owned by the page forgets the position", screen.contains("verticalScroll(rememberScrollState())"))
    }
}
