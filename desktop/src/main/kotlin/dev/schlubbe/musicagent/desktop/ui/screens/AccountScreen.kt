package dev.schlubbe.musicagent.desktop.ui.screens

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.Clock
import com.adamglin.phosphoricons.regular.GearSix
import com.adamglin.phosphoricons.regular.PencilSimple
import com.adamglin.phosphoricons.regular.Sparkle
import com.adamglin.phosphoricons.regular.User
import dev.schlubbe.musicagent.desktop.ui.C
import dev.schlubbe.musicagent.desktop.ui.Canopy
import dev.schlubbe.musicagent.desktop.ui.IconBtn
import dev.schlubbe.musicagent.desktop.ui.LibraryTab
import dev.schlubbe.musicagent.desktop.ui.LocalUi
import dev.schlubbe.musicagent.desktop.ui.Panel
import dev.schlubbe.musicagent.desktop.ui.PrimaryButton
import dev.schlubbe.musicagent.desktop.ui.Route
import dev.schlubbe.musicagent.desktop.ui.SectionHeader
import dev.schlubbe.musicagent.desktop.ui.Shelf
import dev.schlubbe.musicagent.desktop.ui.sourceLabel
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Avatar colour options (settings.profileColorStyle). */
val PROFILE_COLOR_OPTIONS = listOf("auto" to "Auto", "accent" to "Akzent", "accent2" to "Akzent 2", "neutral" to "Neutral")

/** Avatar colour for a profile style; "auto" derives a stable shade from [seed]. */
fun profileColor(style: String, seed: String, c: Canopy): Color = when (style) {
    "accent" -> c.accent
    "accent2" -> c.accent2
    "neutral" -> c.textMuted
    else -> listOf(c.accent, c.accentStrong, Color(0xFF3F7A65), Color(0xFF6FA48E))[Math.floorMod(seed.hashCode(), 4)]
}

/** Listening seconds for the 7 days ending [today] (oldest first). */
fun lastSevenDays(byDay: Map<String, Long>, today: LocalDate = LocalDate.now()): List<Pair<LocalDate, Long>> =
    (6 downTo 0).map { today.minusDays(it.toLong()) }.map { it to (byDay[it.toString()] ?: 0L) }

/** "1 Std. 5 Min" / "12 Min" / "< 1 Min". */
fun formatListenTime(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return when {
        h > 0 -> "$h Std. $m Min"
        m > 0 -> "$m Min"
        else -> if (sec > 0) "< 1 Min" else "0 Min"
    }
}

@Composable
fun AccountScreen() {
    val ui = LocalUi.current
    val g = ui.graph
    val c = C.c
    val settings by g.settings.state.collectAsState()
    val data by g.store.data.collectAsState()
    val days = lastSevenDays(data.listenSecondsByDay)
    val week = days.sumOf { it.second }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 900.dp
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 32.dp, bottom = 48.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Konto", style = MaterialTheme.typography.displayMedium, color = c.text, modifier = Modifier.weight(1f))
                    IconBtn(PhosphorIcons.Regular.GearSix, "Einstellungen", tint = c.text, background = c.surfaceHigh) { ui.navigateRoot(Route.Settings) }
                }
                Spacer(Modifier.height(20.dp))
                ProfileHeader(settings.profileName, settings.profileColorStyle)
            }
            item {
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatTile("${data.likes.size}", "Likes", Modifier.weight(1f)) { ui.navigateRoot(Route.Library(LibraryTab.LIKES)) }
                    StatTile("${data.playlists.size + data.savedPlaylists.size}", "Playlists", Modifier.weight(1f)) { ui.navigateRoot(Route.Library(LibraryTab.PLAYLISTS)) }
                    StatTile("${data.followed.size}", "Folge ich", Modifier.weight(1f)) { ui.navigateRoot(Route.Library(LibraryTab.FOLLOWING)) }
                    if (wide) StatTile("${data.downloads.count { it.state == dev.schlubbe.musicagent.data.local.entity.DownloadState.COMPLETED }}", "Downloads", Modifier.weight(1f)) { ui.navigateRoot(Route.Downloads) }
                }
            }
            item {
                Spacer(Modifier.height(20.dp))
                if (wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ListenPanel(days, week, Modifier.weight(1.6f))
                    AvatarColorPanel(settings.profileColorStyle, settings.profileName, Modifier.weight(1f))
                } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ListenPanel(days, week, Modifier.fillMaxWidth())
                    AvatarColorPanel(settings.profileColorStyle, settings.profileName, Modifier.fillMaxWidth())
                }
            }
            item {
                SectionHeader("Folge ich", action = if (data.followed.isNotEmpty()) "Alle" else null, onAction = { ui.navigateRoot(Route.Library(LibraryTab.FOLLOWING)) })
                if (data.followed.isEmpty()) Text("Du folgst noch niemandem", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                else Shelf {
                    items(data.followed, key = { it.source + it.sourceId }) { a ->
                        val open = { ui.navigate(Route.Artist(a.source, a.sourceId, a.name)) }
                        ContextMenuArea(items = { listOf(ContextMenuItem("Künstler öffnen") { open() }) }) {
                            HomeCard(a.name, sourceLabel(a.source), a.thumbnailUrl, width = 140.dp, circle = true, onClick = open)
                        }
                    }
                }
            }
            item {
                SectionHeader("Personalisierung")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrimaryButton("Musikgeschmack anpassen", PhosphorIcons.Regular.Sparkle, accent = false) { ui.navigate(Route.Onboarding(tasteOnly = true)) }
                    PrimaryButton("Einstellungen", PhosphorIcons.Regular.GearSix, accent = false) { ui.navigateRoot(Route.Settings) }
                }
                val picks = settings.preferredGenres + settings.preferredArtists
                if (picks.isNotEmpty()) Text(
                    picks.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun ProfileHeader(name: String, colorStyle: String) {
    val ui = LocalUi.current
    val c = C.c
    var editing by remember { mutableStateOf(false) }
    var draft by remember(name) { mutableStateOf(name) }
    val focus = remember { FocusRequester() }
    fun save() { ui.graph.settings.update { it.copy(profileName = draft.trim()) }; editing = false }
    LaunchedEffect(editing) { if (editing) runCatching { focus.requestFocus() } }
    val avatar = profileColor(colorStyle, name, c)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(c.accent.copy(alpha = if (c.isDark) 0.35f else 0.16f), c.surface)))
            .padding(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(84.dp).clip(CircleShape).background(avatar), contentAlignment = Alignment.Center) {
            val initial = name.trim().firstOrNull()?.uppercaseChar()
            if (initial != null) Text("$initial", style = MaterialTheme.typography.displayMedium, color = Color.White)
            else Icon(PhosphorIcons.Regular.User, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            if (editing) {
                BasicTextField(
                    draft, { draft = it.take(40) }, singleLine = true,
                    textStyle = MaterialTheme.typography.headlineLarge.copy(color = c.text), cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.widthIn(min = 200.dp, max = 480.dp).focusRequester(focus)
                        .clip(RoundedCornerShape(8.dp)).background(c.surface).border(1.dp, c.accent, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .onPreviewKeyEvent { e ->
                            when {
                                e.type != KeyEventType.KeyDown -> false
                                e.key == Key.Enter -> { save(); true }
                                e.key == Key.Escape -> { draft = name; editing = false; true }
                                else -> false
                            }
                        },
                )
                Text("Enter speichert · Esc bricht ab", style = MaterialTheme.typography.labelSmall, color = c.textFaint, modifier = Modifier.padding(top = 4.dp))
            } else {
                val hover = remember { MutableInteractionSource() }
                val hovered by hover.collectIsHoveredAsState()
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).hoverable(hover).clickable { draft = name; editing = true }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(name.ifBlank { "Name festlegen" }, style = MaterialTheme.typography.headlineLarge, color = if (name.isBlank()) c.textMuted else c.text)
                    Icon(PhosphorIcons.Regular.PencilSimple, "Bearbeiten", tint = if (hovered) c.accent else c.textFaint, modifier = Modifier.size(18.dp))
                }
            }
            Text("Lokales Profil · kein Login erforderlich", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        }
        if (editing) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Abbrechen", accent = false) { draft = name; editing = false }
            PrimaryButton("Speichern", PhosphorIcons.Regular.Check) { save() }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    val c = C.c
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).hoverable(hover).background(if (hovered) c.surfaceHigh else c.surface).clickable(onClick = onClick).padding(20.dp),
    ) {
        Text(value, style = MaterialTheme.typography.displayMedium, color = c.text)
        Text(label, style = MaterialTheme.typography.labelLarge, color = c.textMuted)
    }
}

@Composable
private fun ListenPanel(days: List<Pair<LocalDate, Long>>, week: Long, modifier: Modifier) {
    val c = C.c
    Panel(modifier) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(PhosphorIcons.Regular.Clock, null, tint = c.accent, modifier = Modifier.size(18.dp))
                Text("Hörzeit der letzten 7 Tage", style = MaterialTheme.typography.titleMedium, color = c.text)
            }
            Text(
                HomeLogic.weeklyStatLine(week).ifEmpty { "Diese Woche noch nichts gehört" },
                style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
            )
            val max = (days.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
            val today = LocalDate.now()
            Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                days.forEach { (day, sec) ->
                    val hover = remember(day) { MutableInteractionSource() }
                    val hovered by hover.collectIsHoveredAsState()
                    Column(Modifier.weight(1f).fillMaxHeight().hoverable(hover), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        Text(
                            if (hovered || day == today) formatListenTime(sec) else "", style = MaterialTheme.typography.labelSmall,
                            color = c.textMuted, maxLines = 1,
                        )
                        Spacer(Modifier.height(4.dp))
                        Box(
                            Modifier.fillMaxWidth().weight(1f, fill = false)
                                .height((100f * sec / max).coerceAtLeast(4f).dp)
                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                .background(if (day == today) c.accent2 else if (hovered) c.accentStrong else c.accent),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMAN).take(2), style = MaterialTheme.typography.labelSmall,
                            color = if (day == today) c.text else c.textFaint, fontWeight = if (day == today) FontWeight.Bold else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AvatarColorPanel(style: String, name: String, modifier: Modifier) {
    val ui = LocalUi.current
    val c = C.c
    Panel(modifier) {
        Column {
            Text("Avatarfarbe", style = MaterialTheme.typography.titleMedium, color = c.text)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PROFILE_COLOR_OPTIONS.forEach { (key, label) ->
                    val selected = style == key
                    Column(
                        Modifier.clip(RoundedCornerShape(10.dp)).clickable { ui.graph.settings.update { it.copy(profileColorStyle = key) } }.padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(profileColor(key, name, c))
                                .border(if (selected) 3.dp else 0.dp, if (selected) c.text else Color.Transparent, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { if (selected) Icon(PhosphorIcons.Regular.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                        Spacer(Modifier.height(6.dp))
                        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) c.text else c.textMuted)
                    }
                }
            }
        }
    }
}
