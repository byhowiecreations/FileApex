package com.fileapex.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Test

/** A header title that does not fit whole beside its action buttons must disappear, never show as a clipped fragment or an ellipsis. */
class HeaderTitleFitTest {
    private fun titleWidth(sceneWidth: Int, actionWidth: Int): Int {
        var width = -1 // stays -1 when the title is never placed
        val scene = ImageComposeScene(width = sceneWidth, height = 200, density = Density(1f)) {
            Row {
                Column(modifier = Modifier.weight(1f).hiddenUnlessItFits().onGloballyPositioned { width = it.size.width }) {
                    Text("FileApex", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(modifier = Modifier.size(actionWidth.dp, 40.dp))
            }
        }
        scene.render()
        scene.close()
        return width
    }

    @Test
    fun `title is not placed when its whole text does not fit`() {
        assertTrue("title was placed", titleWidth(sceneWidth = 400, actionWidth = 380) <= 0)
    }

    @Test
    fun `title is placed when there is room`() {
        assertTrue(titleWidth(sceneWidth = 400, actionWidth = 100) > 0)
    }
}
