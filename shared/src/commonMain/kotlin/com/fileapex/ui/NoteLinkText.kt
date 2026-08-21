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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import com.fileapex.platform.PlatformClipboard
import com.fileapex.platform.normalizeWebUrlToken
import com.fileapex.platform.webUrlMatchesInText

@Composable
fun NoteLinkText(
    text: String,
    style: TextStyle,
    color: Color,
    linkColor: Color,
    modifier: Modifier = Modifier
) {
    val matches = remember(text) { webUrlMatchesInText(text) }
    val linkListener = remember {
        LinkInteractionListener { link ->
            val url = (link as? LinkAnnotation.Url)?.url ?: return@LinkInteractionListener
            PlatformClipboard.openUrlInDefaultBrowser(url)
        }
    }

    SelectionContainer(modifier = modifier) {
        if (matches.isEmpty()) {
            Text(text = text, style = style, color = color)
            return@SelectionContainer
        }

        val annotated = buildAnnotatedString {
            var cursor = 0
            for (match in matches) {
                val start = match.range.first
                if (start > cursor) {
                    withStyle(SpanStyle(color = color)) {
                        append(text.substring(cursor, start))
                    }
                }
                val rawUrl = match.value
                val url = normalizeWebUrlToken(rawUrl)
                withLink(
                    LinkAnnotation.Url(
                        url = url,
                        linkInteractionListener = linkListener
                    )
                ) {
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                        append(rawUrl)
                    }
                }
                cursor = match.range.last + 1
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
