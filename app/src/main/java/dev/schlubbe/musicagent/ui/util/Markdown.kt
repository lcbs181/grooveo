package dev.schlubbe.musicagent.ui.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

// Shared with the desktop app (its build copies this file).

private val INLINE = Regex("""\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])|(?<!\w)_(?!\s)(.+?)(?<!\s)_(?!\w)""")
private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val NUMBERED = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
private val HEADING = Regex("""^#{1,6}\s+(.*)$""")

/**
 * The simple Markdown models write in replies: **bold**, *italic*, `code`, "- " bullets,
 * numbered lists and headings. Anything else stays as written.
 */
fun markdown(text: String, codeBackground: Color = Color.Unspecified): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { i, line ->
        if (i > 0) append('\n')
        val bullet = BULLET.matchEntire(line)
        val numbered = NUMBERED.matchEntire(line)
        val heading = HEADING.matchEntire(line)
        when {
            bullet != null -> { append(bullet.groupValues[1] + "• "); inline(bullet.groupValues[2], codeBackground) }
            numbered != null -> { append("${numbered.groupValues[1]}${numbered.groupValues[2]}. "); inline(numbered.groupValues[3], codeBackground) }
            heading != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { inline(heading.groupValues[1], codeBackground) }
            else -> inline(line, codeBackground)
        }
    }
}

private fun AnnotatedString.Builder.inline(s: String, codeBackground: Color) {
    var at = 0
    for (m in INLINE.findAll(s)) {
        append(s.substring(at, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() || g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { inline(g[1].ifEmpty { g[2] }, codeBackground) }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(g[3]) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inline(g[4].ifEmpty { g[5] }, codeBackground) }
        }
        at = m.range.last + 1
    }
    append(s.substring(at))
}
