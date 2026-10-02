package dev.schlubbe.musicagent.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.CaretUp
import com.adamglin.phosphoricons.regular.Clock
import dev.schlubbe.musicagent.desktop.ui.C
import dev.schlubbe.musicagent.desktop.ui.Canopy
import dev.schlubbe.musicagent.desktop.ui.Cover

/** Page side padding shared by the library/detail pages. */
val PagePad: Dp = 32.dp

/** Header colour for a playlist's accentColorKey (Auto/Akzent/Akzent 2/Neutral). */
fun accentFor(key: String?, c: Canopy): Color = when (key) {
    "accent2" -> c.accent2
    "neutral" -> c.textFaint
    else -> c.accent
}

/**
 * Big detail-page header: gradient from [accent] into the page background, cover
 * (200dp), small label, display title and meta lines.
 */
@Composable
fun DetailHeader(
    coverUrl: String?,
    label: String,
    title: String,
    accent: Color,
    circle: Boolean = false,
    coverSize: Dp = 200.dp,
    meta: @Composable ColumnScope.() -> Unit = {},
) {
    val c = C.c
    Box(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(accent.copy(alpha = if (c.isDark) 0.55f else 0.35f), Color.Transparent)))
            .padding(start = PagePad, end = PagePad, top = 40.dp, bottom = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Cover(coverUrl, coverSize, radius = 12.dp, circle = circle)
            Spacer(Modifier.width(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                Text(title, style = MaterialTheme.typography.displayMedium, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                meta()
            }
        }
    }
}

/** Action row below a header (play, shuffle, …). */
@Composable
fun ActionBar(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PagePad, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** Column header of the desktop track table; clicking a column sorts. */
@Composable
fun TrackTableHeader(sort: TrackSort, onSort: (TrackSort) -> Unit, modifier: Modifier = Modifier, showIndex: Boolean = true) {
    val c = C.c
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showIndex) HeaderCell("#", sort, TrackColumn.INDEX, onSort, Modifier.width(32.dp))
            Spacer(Modifier.width(56.dp))
            HeaderCell("Titel", sort, TrackColumn.TITLE, onSort)
            Spacer(Modifier.width(16.dp))
            HeaderCell("Künstler", sort, TrackColumn.ARTIST, onSort)
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(52.dp).clickable { onSort(sort.click(TrackColumn.DURATION)) }, contentAlignment = Alignment.CenterStart) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(PhosphorIcons.Regular.Clock, "Dauer", tint = if (sort.column == TrackColumn.DURATION) c.accent2 else c.textFaint, modifier = Modifier.padding(start = 8.dp).size(14.dp))
                    SortCaret(sort, TrackColumn.DURATION)
                }
            }
        }
        HorizontalDivider(color = c.divider)
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun HeaderCell(text: String, sort: TrackSort, col: TrackColumn, onSort: (TrackSort) -> Unit, modifier: Modifier = Modifier) {
    val c = C.c
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val active = sort.column == col && col != TrackColumn.INDEX
    Row(
        modifier.hoverable(hover).clickable { onSort(sort.click(col)) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (active) c.accent2 else if (hovered) c.text else c.textFaint)
        SortCaret(sort, col)
    }
}

@Composable
private fun SortCaret(sort: TrackSort, col: TrackColumn) {
    if (sort.column != col || col == TrackColumn.INDEX) return
    Icon(if (sort.ascending) PhosphorIcons.Regular.CaretUp else PhosphorIcons.Regular.CaretDown, null, tint = C.c.accent2, modifier = Modifier.padding(start = 2.dp).size(12.dp))
}

/** Small "Mehr anzeigen"/"Weniger anzeigen" expandable text (playlist / artist descriptions). */
@Composable
fun ExpandableText(text: String, modifier: Modifier = Modifier, collapsedLines: Int = 2) {
    var expanded by remember(text) { mutableStateOf(false) }
    var overflowing by remember(text) { mutableStateOf(false) }
    Column(modifier) {
        Text(
            text, style = MaterialTheme.typography.bodyMedium, color = C.c.textMuted,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
        )
        if (overflowing || expanded) Text(
            if (expanded) "Weniger anzeigen" else "Mehr anzeigen",
            style = MaterialTheme.typography.labelLarge, color = C.c.accent, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded }.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}


@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = C.c
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(title, color = c.text) },
        text = { Text(text, color = c.textMuted) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = c.accent2) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen", color = c.textMuted) } },
    )
}

/** Single-line name prompt (e.g. "Neue Playlist"). Enter confirms, Esc cancels. */
@Composable
fun NameDialog(title: String, initial: String = "", confirm: String = "Erstellen", label: String = "Name", onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val c = C.c
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(title, color = c.text) },
        text = {
            OutlinedTextField(
                name, { name = it }, label = { Text(label) }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(name); onDismiss() }),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name); onDismiss() }) { Text(confirm, color = c.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen", color = c.textMuted) } },
    )
}

@Composable
fun fieldColors() = C.c.let { c ->
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.text, unfocusedTextColor = c.text, focusedBorderColor = c.accent, unfocusedBorderColor = c.divider,
        cursorColor = c.accent, focusedLabelColor = c.accent, unfocusedLabelColor = c.textMuted,
        focusedPlaceholderColor = c.textFaint, unfocusedPlaceholderColor = c.textFaint,
        focusedLeadingIconColor = c.textMuted, unfocusedLeadingIconColor = c.textMuted,
        focusedTrailingIconColor = c.textMuted, unfocusedTrailingIconColor = c.textMuted,
    )
}
