package dev.schlubbe.musicagent.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.CloudArrowUp
import com.adamglin.phosphoricons.regular.Cube
import com.adamglin.phosphoricons.regular.DownloadSimple
import com.adamglin.phosphoricons.regular.Keyboard
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.Pulse
import com.adamglin.phosphoricons.regular.Shuffle
import com.adamglin.phosphoricons.regular.SlidersHorizontal
import com.adamglin.phosphoricons.regular.Sparkle
import com.adamglin.phosphoricons.regular.Waveform
import dev.schlubbe.musicagent.desktop.APP_VERSION
import dev.schlubbe.musicagent.desktop.data.key
import dev.schlubbe.musicagent.desktop.ui.C
import dev.schlubbe.musicagent.desktop.ui.Cover
import dev.schlubbe.musicagent.desktop.ui.LocalUi
import dev.schlubbe.musicagent.desktop.ui.PrimaryButton
import java.io.File

/** "Zu Playlist hinzufügen" for [dev.schlubbe.musicagent.desktop.ui.AppUi.addToPlaylist]. */
@Composable
fun AddToPlaylistDialog() {
    val ui = LocalUi.current
    val tracks = ui.addToPlaylist ?: return
    val g = ui.graph
    val c = C.c
    val data by g.store.data.collectAsState()
    var name by remember { mutableStateOf("") }
    val dismiss = { ui.addToPlaylist = null }
    val what = if (tracks.size == 1) "„${tracks.first().title}“" else "${tracks.size} Titel"
    fun create() {
        val p = g.store.createPlaylist(name.ifBlank { "Neue Playlist" }, tracks)
        ui.toast("Playlist „${p.name}“ erstellt")
        dismiss()
    }
    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = c.surface,
        title = {
            Column {
                Text("Zu Playlist hinzufügen", style = MaterialTheme.typography.headlineMedium, color = c.text)
                Text(what, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(Modifier.widthIn(min = 380.dp)) {
                if (data.playlists.isEmpty()) Text("Noch keine Playlists – leg unten die erste an.", style = MaterialTheme.typography.bodyMedium, color = c.textMuted, modifier = Modifier.padding(vertical = 8.dp))
                else LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(data.playlists, key = { it.id }) { p ->
                        val keys = remember(p.tracks) { p.tracks.map { it.track.key }.toSet() }
                        val already = tracks.all { it.key in keys }
                        val hover = remember { MutableInteractionSource() }
                        val hovered by hover.collectIsHoveredAsState()
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).hoverable(hover)
                                .background(if (hovered) c.surfaceHigh else c.surface)
                                .clickable {
                                    g.store.addToPlaylist(p.id, tracks)
                                    ui.toast(if (already) "Bereits in „${p.name}“" else "Zu „${p.name}“ hinzugefügt")
                                    dismiss()
                                }.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val cover = p.coverPath?.takeIf { File(it).isFile }?.let { File(it).toURI().toString() }
                                ?: p.tracks.firstOrNull { it.track.thumbnailUrl != null }?.track?.thumbnailUrl
                            Cover(cover, 44.dp, radius = 6.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${p.tracks.size} Titel", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                            }
                            if (already) Icon(PhosphorIcons.Regular.Check, "Bereits enthalten", tint = c.accent, modifier = Modifier.size(18.dp))
                            else if (hovered) Icon(PhosphorIcons.Regular.Plus, null, tint = c.accent, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = c.divider)
                Text("Neue Playlist", style = MaterialTheme.typography.titleSmall, color = c.text)
                Spacer(Modifier.size(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        name, { name = it }, singleLine = true, modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                            if (e.key == Key.Enter && e.type == KeyEventType.KeyDown) { create(); true } else false
                        },
                        placeholder = { Text("Name der Playlist", color = c.textFaint) },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = c.accent, unfocusedBorderColor = c.divider, cursorColor = c.accent,
                            focusedTextColor = c.text, unfocusedTextColor = c.text,
                        ),
                    )
                    PrimaryButton("Erstellen & hinzufügen", PhosphorIcons.Regular.Plus) { create() }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = dismiss) { Text("Abbrechen", color = c.textMuted) } },
    )
}

private data class WhatsNewItem(val icon: ImageVector, val title: String, val description: String)

private val DESKTOP_CHANGES = listOf(
    WhatsNewItem(PhosphorIcons.Regular.Sparkle, "Grooveo jetzt auf dem Desktop", "Dieselbe Bibliothek, dieselben Quellen wie auf Android – mit breitem Layout, Rechtsklick-Menüs, Hover-Steuerung und Tastenkürzeln."),
    WhatsNewItem(PhosphorIcons.Regular.SlidersHorizontal, "Parametrischer Equalizer", "Bis zu 16 Bänder mit analog-getreuen Filtern (Vicanek) in 64-Bit, Subsonic-Filter, Bass-Enhancer und Loudness-Kompensation für satten Bass. Kopfhörer-Profile per AutoEQ-Import."),
    WhatsNewItem(PhosphorIcons.Regular.Shuffle, "Crossfade & lückenlose Wiedergabe", "Titel gehen nahtlos ineinander über. Mit „Übergänge analysieren“ setzt Grooveo die Überblendung an die passenden Stellen im Song."),
    WhatsNewItem(PhosphorIcons.Regular.Cube, "3D-Sound", "Raumklang-Voreinstellungen per Faltungshall – von kleinem Raum bis Konzertsaal."),
    WhatsNewItem(PhosphorIcons.Regular.Waveform, "Visualizer", "Animierte Visualizer im großen Player, die in Echtzeit auf das Spektrum reagieren."),
    WhatsNewItem(PhosphorIcons.Regular.Keyboard, "MPRIS & Medientasten", "Steuerung über die Medientasten der Tastatur, das Desktop-Panel und andere MPRIS-Clients – plus Tastenkürzel in der App."),
    WhatsNewItem(PhosphorIcons.Regular.DownloadSimple, "Downloads", "Titel und ganze Playlists offline speichern – auch SoundCloud-HLS-Streams, die mit FFmpeg umgewandelt werden."),
    WhatsNewItem(PhosphorIcons.Regular.CloudArrowUp, "Sicherung kompatibel mit Android", "Likes, Playlists, Verlauf und Einstellungen als Datei sichern und auf Android oder dem Desktop wiederherstellen."),
    WhatsNewItem(PhosphorIcons.Regular.Pulse, "Startseite mit Mixes", "Gym Hardstyle Mix🔱, Fokus-, Chill- und Party-Mix, „Für dich“, Trends nach Genre und täglich neu gemischte Charts."),
)

/** Changelog shown once after an update (ui.showWhatsNew). */
@Composable
fun WhatsNewDialog() {
    val ui = LocalUi.current
    if (!ui.showWhatsNew) return
    val c = C.c
    val close = {
        ui.graph.settings.update { it.copy(lastSeenVersion = APP_VERSION) }
        ui.showWhatsNew = false
    }
    AlertDialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(max = 640.dp).padding(24.dp),
        containerColor = c.bg,
        title = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
                    Icon(PhosphorIcons.Regular.Sparkle, null, tint = c.accent, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.size(14.dp))
                Text("Neu in $APP_VERSION", style = MaterialTheme.typography.headlineLarge, color = c.text, textAlign = TextAlign.Center)
                Text("Grooveo für den Desktop", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
            }
        },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DESKTOP_CHANGES.forEach { item ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(c.surface), contentAlignment = Alignment.Center) {
                            Icon(item.icon, null, tint = c.accent, modifier = Modifier.size(20.dp))
                        }
                        Column(Modifier.padding(start = 14.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium, color = c.text)
                            Text(item.description, style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { PrimaryButton("Los geht's", onClick = close) },
    )
}
