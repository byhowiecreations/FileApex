package com.fileapex.ui

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import com.fileapex.platform.PlatformClipboard
import com.fileapex.platform.normalizeWebUrlToken
import com.fileapex.platform.webUrlMatchesInText

private val HASHTAG_REGEX = Regex("""#[A-Za-z0-9_]+""")

private sealed interface TextSpanToken {
    val range: IntRange
    data class Url(override val range: IntRange, val rawUrl: String) : TextSpanToken
    data class Hashtag(override val range: IntRange, val tag: String) : TextSpanToken
}

@Composable
fun NoteLinkText(
    text: String,
    style: TextStyle,
    color: Color,
    linkColor: Color,
    modifier: Modifier = Modifier,
    onHashtagClick: ((String) -> Unit)? = null
) {
    val tokens = remember(text) {
        val urlMatches = webUrlMatchesInText(text).map { match ->
            TextSpanToken.Url(match.range, match.value)
        }
        val tagMatches = HASHTAG_REGEX.findAll(text).map { match ->
            TextSpanToken.Hashtag(match.range, match.value)
        }.toList()

        // Combine and filter out overlapping tokens, preferring URLs if there's any clash
        val combined = (urlMatches + tagMatches).sortedBy { it.range.first }
        val nonOverlapping = mutableListOf<TextSpanToken>()
        var lastEnd = -1
        for (token in combined) {
            if (token.range.first >= lastEnd) {
                nonOverlapping += token
                lastEnd = token.range.last + 1
            }
        }
        nonOverlapping
    }

    val urlListener = remember {
        LinkInteractionListener { link ->
            val url = (link as? LinkAnnotation.Url)?.url ?: return@LinkInteractionListener
            PlatformClipboard.openUrlInDefaultBrowser(url)
        }
    }

    SelectionContainer(modifier = modifier) {
        if (tokens.isEmpty()) {
            Text(text = text, style = style, color = color)
            return@SelectionContainer
        }

        val annotated = buildAnnotatedString {
            var cursor = 0
            for (token in tokens) {
                val start = token.range.first
                if (start > cursor) {
                    withStyle(SpanStyle(color = color)) {
                        append(text.substring(cursor, start))
                    }
                }
                when (token) {
                    is TextSpanToken.Url -> {
                        val rawUrl = token.rawUrl
                        val url = normalizeWebUrlToken(rawUrl)
                        withLink(
                            LinkAnnotation.Url(
                                url = url,
                                linkInteractionListener = urlListener
                            )
                        ) {
                            withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                                append(rawUrl)
                            }
                        }
                    }
                    is TextSpanToken.Hashtag -> {
                        val tag = token.tag
                        if (onHashtagClick != null) {
                            withLink(
                                LinkAnnotation.Clickable(
                                    tag = "hashtag",
                                    linkInteractionListener = { onHashtagClick(tag) }
                                )
                            ) {
                                withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Bold)) {
                                    append(tag)
                                }
                            }
                        } else {
                            withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Bold)) {
                                append(tag)
                            }
                        }
                    }
                }
                cursor = token.range.last + 1
            }
            if (cursor < text.length) {
                withStyle(SpanStyle(color = color)) {
                    append(text.substring(cursor))
                }
            }
        }

        Text(text = annotated, style = style.copy(color = color))
    }
}
