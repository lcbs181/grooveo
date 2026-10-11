package dev.schlubbe.musicagent.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowUp
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.Prohibit
import com.adamglin.phosphoricons.regular.Sparkle
import com.adamglin.phosphoricons.regular.Stop
import com.adamglin.phosphoricons.regular.Warning
import com.adamglin.phosphoricons.regular.X
import dev.schlubbe.musicagent.desktop.ai.AiItem
import dev.schlubbe.musicagent.desktop.ai.AiPolicy
import dev.schlubbe.musicagent.desktop.ui.C
import dev.schlubbe.musicagent.desktop.ui.IconBtn
import dev.schlubbe.musicagent.desktop.ui.LocalUi
import dev.schlubbe.musicagent.desktop.ui.Pill
import dev.schlubbe.musicagent.desktop.ui.SidePanel

private val SUGGESTIONS = listOf(
    "Spiel was Ähnliches wie diesen Titel",
    "Mach mir eine Playlist zum Lernen ohne Gesang",
    "Bau mir eine FX-Kette für Club-Sound",
    "Welche meiner Playlists haben doppelte Titel?",
)

/** "Grooveo-Assistent": chat with the in-app AI, its actions and its questions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiPanel(modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val ai = ui.graph.ai
    val c = C.c
    val st by ai.state.collectAsState()
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(st.items.size, (st.items.lastOrNull() as? AiItem.Reply)?.text?.length) {
        if (st.items.isNotEmpty()) list.animateScrollToItem(st.items.size - 1)
    }
    LaunchedEffect(ui.aiFocusRequest) { runCatching { focus.requestFocus() } }
    fun send(text: String = input) {
        if (text.isBlank() || st.running) return
        ai.send(text)
        input = ""
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(PhosphorIcons.Regular.Sparkle, null, tint = c.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Grooveo-Assistent", style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.weight(1f))
            TextButton({ ai.newConversation() }) { Text("Neu", color = c.textMuted) }
            IconBtn(PhosphorIcons.Regular.X, "Schließen") { ui.togglePanel(SidePanel.AI) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (st.items.isEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Text("Sag Grooveo, was es tun soll: Musik finden, Playlists bauen, Klang einstellen, Übergänge planen.", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SUGGESTIONS.forEach { s -> Pill(s, false) { send(s) } }
                    }
                }
            }
            items(st.items, key = { it.id }) { item -> AiItemView(item) { id, allow -> ai.answer(id, allow) } }
        }
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.bg).border(1.dp, c.divider, RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    if (input.isEmpty()) Text("Sag Grooveo, was es tun soll …", style = MaterialTheme.typography.bodyMedium, color = c.textFaint)
                    BasicTextField(
                        input, { input = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text),
                        cursorBrush = SolidColor(c.accent),
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 20.dp).focusRequester(focus).onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) { send(); true } else false
                        },
                    )
                }
                if (st.running) IconBtn(PhosphorIcons.Regular.Stop, "Anhalten", tint = c.accent2) { ai.stop() }
                else IconBtn(PhosphorIcons.Regular.ArrowUp, "Senden (Enter)", tint = if (input.isBlank()) c.textFaint else c.accent, enabled = input.isNotBlank()) { send() }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(st.provider.ifBlank { ai.providerLabel() }, style = MaterialTheme.typography.labelSmall, color = c.textFaint, modifier = Modifier.weight(1f))
                if (st.canUndo && !st.running) TextButton({ ai.undoLast() }) {
                    Text(st.undoFor?.let { "„$it“ rückgängig" } ?: "Letzte Antwort rückgängig", color = c.accent, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun AiItemView(item: AiItem, answer: (Long, Boolean) -> Unit) {
    val c = C.c
    when (item) {
        is AiItem.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                item.text, style = MaterialTheme.typography.bodyMedium, color = c.text,
                modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp)).background(c.accentSoft).padding(horizontal = 12.dp, vertical = 9.dp),
            )
        }
        is AiItem.Reply -> if (item.text.isEmpty() && item.streaming) {
            CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
        } else {
            val codeBg = c.accentSoft
            Text(remember(item.text, codeBg) { markdown(item.text, codeBg) }, style = MaterialTheme.typography.bodyMedium, color = c.text)
        }
        is AiItem.Action -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (item.status) {
                AiItem.Action.Status.RUNNING -> CircularProgressIndicator(Modifier.size(14.dp), color = c.accent, strokeWidth = 2.dp)
                AiItem.Action.Status.OK -> Icon(PhosphorIcons.Regular.Check, null, tint = c.accent, modifier = Modifier.size(14.dp))
                AiItem.Action.Status.FAILED -> Icon(PhosphorIcons.Regular.Warning, null, tint = c.accent2, modifier = Modifier.size(14.dp))
                AiItem.Action.Status.DECLINED -> Icon(PhosphorIcons.Regular.Prohibit, null, tint = c.textFaint, modifier = Modifier.size(14.dp))
            }
            Column {
                Text(item.label + if (item.status == AiItem.Action.Status.DECLINED) " · abgelehnt" else "", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                item.detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = c.accent2) }
            }
        }
        is AiItem.Confirm -> {
            val warn = item.kind != AiPolicy.Kind.FX
            val edge = if (warn) c.accent2 else c.accent
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (warn) c.accent2Soft else c.accentSoft).border(1.dp, edge, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = c.text)
                Text(item.detail, style = MaterialTheme.typography.bodySmall, color = c.text)
                when (item.answer) {
                    null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(if (item.kind == AiPolicy.Kind.DELETE) "Löschen" else "Erlauben", true) { answer(item.id, true) }
                        Pill(if (item.kind == AiPolicy.Kind.LOUDNESS) "Lautstärke lassen" else "Ablehnen", false) { answer(item.id, false) }
                    }
                    true -> Text("Erlaubt", style = MaterialTheme.typography.labelMedium, color = c.accent)
                    false -> Text("Abgelehnt", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                }
            }
        }
        is AiItem.Notice -> Text(item.text, style = MaterialTheme.typography.bodySmall, color = if (item.error) c.accent2 else c.textMuted)
    }
}

private val INLINE = Regex("""\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])|(?<!\w)_(?!\s)(.+?)(?<!\s)_(?!\w)""")
private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val NUMBERED = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
private val HEADING = Regex("""^#{1,6}\s+(.*)$""")

/**
 * The simple Markdown models write in replies: **bold**, *italic*, `code`, "- " bullets,
 * numbered lists and headings. Anything else stays as written.
 */
internal fun markdown(text: String, codeBackground: Color = Color.Unspecified): AnnotatedString = buildAnnotatedString {
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
