package com.fileapex.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackRedditOptionTest {
    @Test
    fun theRedditCardOpensTheFileApexSubreddit() {
        assertEquals("https://www.reddit.com/r/fileapex", FEEDBACK_REDDIT_URL)
    }

    @Test
    fun theRedditCardHasATitleAndDescriptionInEveryLanguage() {
        val dir = File("src/commonMain/composeResources/files/i18n")
        listOf("en.xml", "es.xml", "zh-rCN.xml").forEach { name ->
            val text = File(dir, name).readText()
            assertTrue("$name needs feedback_option_reddit", text.contains("name=\"feedback_option_reddit\""))
            assertTrue("$name needs feedback_option_reddit_desc", text.contains("name=\"feedback_option_reddit_desc\""))
        }
    }
}
