package com.sysadmin.lasstore.ui.catalog

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import com.sysadmin.lasstore.ui.theme.Catppuccin

private const val MAX_MARKDOWN_CHARS = 16 * 1024
private val inlineMarkdown = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|(?<!\\*)\\*([^*]+)\\*")

@Composable
internal fun MarkdownReleaseNotes(
    raw: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = markdownToAnnotatedString(raw),
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall.copy(color = Catppuccin.Subtext),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

internal fun markdownToAnnotatedString(raw: String): AnnotatedString {
    val bounded = raw.take(MAX_MARKDOWN_CHARS)
    return buildAnnotatedString {
        bounded.lineSequence().forEachIndexed { index, sourceLine ->
            if (index > 0) append("\n")
            val line = sourceLine.trimEnd()
            val heading = line.matchHeading()
            when {
                heading != null -> {
                    pushStyle(
                        SpanStyle(
                            color = Catppuccin.TextStrong,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        ),
                    )
                    appendMarkdownInline(heading)
                    pop()
                }
                line.startsWith("> ") -> {
                    pushStyle(
                        SpanStyle(
                            color = Catppuccin.Subtext,
                            fontStyle = FontStyle.Italic,
                        ),
                    )
                    append("│ ")
                    appendMarkdownInline(line.removePrefix("> "))
                    pop()
                }
                line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") -> {
                    append("• ")
                    appendMarkdownInline(line.substring(2))
                }
                else -> appendMarkdownInline(line)
            }
        }
    }
}

private fun String.matchHeading(): String? {
    val match = Regex("^#{1,6}\\s+(.+)$").matchEntire(trim()) ?: return null
    return match.groupValues[1]
}

private fun AnnotatedString.Builder.appendMarkdownInline(raw: String) {
    var cursor = 0
    inlineMarkdown.findAll(raw).forEach { match ->
        append(raw.substring(cursor, match.range.first))
        val bold = match.groupValues[1].ifBlank { match.groupValues[2] }
        val code = match.groupValues[3]
        val italic = match.groupValues[4]
        when {
            bold.isNotBlank() -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(bold)
                pop()
            }
            code.isNotBlank() -> {
                pushStyle(SpanStyle(color = Catppuccin.Sapphire))
                append(code)
                pop()
            }
            italic.isNotBlank() -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(italic)
                pop()
            }
        }
        cursor = match.range.last + 1
    }
    append(raw.substring(cursor))
}
