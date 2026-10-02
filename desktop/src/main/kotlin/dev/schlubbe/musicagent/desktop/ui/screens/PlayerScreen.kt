package dev.schlubbe.musicagent.desktop.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Heart
import com.adamglin.phosphoricons.regular.ArrowsClockwise
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.CheckCircle
import com.adamglin.phosphoricons.regular.Cloud
import com.adamglin.phosphoricons.regular.Heart
import com.adamglin.phosphoricons.regular.Microphone
import com.adamglin.phosphoricons.regular.MusicNote
import com.adamglin.phosphoricons.regular.PlusCircle
import com.adamglin.phosphoricons.regular.Prohibit
import com.adamglin.phosphoricons.regular.Queue
import com.adamglin.phosphoricons.regular.ShareNetwork
import com.adamglin.phosphoricons.regular.UserCheck
import com.adamglin.phosphoricons.regular.UserPlus
import com.adamglin.phosphoricons.regular.Waveform
import dev.schlubbe.musicagent.data.local.entity.DownloadState
import dev.schlubbe.musicagent.desktop.audio.EqProfile
import dev.schlubbe.musicagent.desktop.audio.Sound3dPreset
import dev.schlubbe.musicagent.desktop.data.FollowedArtist
import dev.schlubbe.musicagent.desktop.data.key
import dev.schlubbe.musicagent.desktop.ui.C
import dev.schlubbe.musicagent.desktop.ui.Cover
import dev.schlubbe.musicagent.desktop.ui.DarkCanopy
import dev.schlubbe.musicagent.desktop.ui.EmptyState
import dev.schlubbe.musicagent.desktop.ui.IconBtn
import dev.schlubbe.musicagent.desktop.ui.LocalCanopy
import dev.schlubbe.musicagent.desktop.ui.LocalUi
import dev.schlubbe.musicagent.desktop.ui.PrimaryButton
import dev.schlubbe.musicagent.desktop.ui.Route
import dev.schlubbe.musicagent.desktop.ui.SeekBar
import dev.schlubbe.musicagent.desktop.ui.SidePanel
import dev.schlubbe.musicagent.desktop.ui.TransportControls
import dev.schlubbe.musicagent.desktop.ui.VolumeControl
import dev.schlubbe.musicagent.desktop.ui.formatBytes
import dev.schlubbe.musicagent.desktop.ui.formatDuration
import dev.schlubbe.musicagent.desktop.ui.sourceLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Full-content "Now Playing" view (Android PlayerScreen), always in the dark Canopy palette over blurred artwork. */
@Composable
fun PlayerScreen() {
    CompositionLocalProvider(LocalCanopy provides DarkCanopy) { PlayerContent() }
}

@Composable
private fun PlayerContent() {
    val ui = LocalUi.current
    val g = ui.graph
    val c = C.c
    val q by g.player.state.collectAsState()
    val e by g.player.engineState.collectAsState()
    val settings by g.settings.state.collectAsState()
    val t = q.current

    Box(Modifier.fillMaxSize().background(c.bg)) {
        // blurred artwork backdrop, tinted towards the palette
        Crossfade(t?.thumbnailUrl, Modifier.fillMaxSize()) { url ->
            if (!url.isNullOrBlank()) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(90.dp).alpha(0.55f))
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c.bg.copy(alpha = 0.35f), c.bg.copy(alpha = 0.7f), c.bg.copy(alpha = 0.92f)))))

        if (t == null) {
            Column(Modifier.fillMaxSize()) {
                PlayerTopBar(null)
                EmptyState(PhosphorIcons.Regular.MusicNote, "Nichts läuft", "Wähle einen Titel aus, um ihn hier zu sehen.")
            }
            return@Box
        }

        Column(Modifier.fillMaxSize()) {
            PlayerTopBar(sourceLabel(t.source))
            Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                // stage: artwork or visualizer
                BoxWithConstraints(Modifier.weight(1.1f).fillMaxHeight().padding(bottom = 24.dp), contentAlignment = Alignment.Center) {
                    val side = minOf(maxWidth, maxHeight)
                    if (q.error != null) {
                        UnavailableState(q.error!!, t.thumbnailUrl) { g.player.next() }
                    } else if (settings.vizVariant == "none") {
                        Cover(t.thumbnailUrl, side * 0.88f, radius = 20.dp)
                    } else {
                        Visualizer(
                            settings.vizVariant, e.playing, { g.player.spectrum.latest }, c.accent2,
                            Modifier.size(side),
                        )
                    }
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    TrackInfo()
                    Column {
                        val dur = e.durationSec ?: t.durationSec?.toDouble()
                        SeekBar(e.positionSec, dur, t.key, settings.playerStyle, height = 34.dp) { g.player.seek(it) }
                        Row {
                            Text(formatDuration(e.positionSec), style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                            Spacer(Modifier.weight(1f))
                            Text(formatDuration(dur), style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TransportControls(big = true)
                        Spacer(Modifier.weight(1f))
                        VolumeControl()
                    }
                    OfflineCard()
                    SoundCard()
                    SleepTimerCard()
                }
            }
        }
    }
}

@Composable
private fun PlayerTopBar(source: String?) {
    val ui = LocalUi.current
    val g = ui.graph
    val c = C.c
    val settings by g.settings.state.collectAsState()
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(PhosphorIcons.Regular.CaretDown, "Schließen (Esc)", tint = c.text, background = Color.White.copy(alpha = 0.08f)) { ui.playerOpen = false }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("LÄUFT GERADE", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
            Text(source ?: "Wiedergabe", style = MaterialTheme.typography.titleSmall, color = c.text)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            VISUALIZER_VARIANTS.forEach { (id, label) ->
                SmallPill(label, settings.vizVariant == id) { g.settings.update { it.copy(vizVariant = id) } }
            }
        }
        Spacer(Modifier.width(16.dp))
        IconBtn(PhosphorIcons.Regular.Microphone, "Songtext", tint = if (ui.panel == SidePanel.LYRICS) c.accent2 else c.textMuted) { ui.togglePanel(SidePanel.LYRICS) }
        IconBtn(PhosphorIcons.Regular.Queue, "Warteschlange", tint = if (ui.panel == SidePanel.QUEUE) c.accent2 else c.textMuted) { ui.togglePanel(SidePanel.QUEUE) }
    }
}

@Composable
private fun SmallPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = C.c
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = if (selected) Color.White else c.textMuted,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) c.accent else Color.White.copy(alpha = 0.07f))
            .clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 5.dp),
    )
}

@Composable
private fun UnavailableState(message: String, art: String?, onSkip: () -> Unit) {
    val c = C.c
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Cover(art, 220.dp, radius = 20.dp, modifier = Modifier.alpha(0.35f))
            Icon(PhosphorIcons.Regular.Prohibit, null, tint = c.accent2, modifier = Modifier.size(56.dp))
        }
        Text(if (message.contains("DRM", true) || message == "Titel nicht verfügbar") "Titel nicht verfügbar" else "Wiedergabe fehlgeschlagen",
            style = MaterialTheme.typography.titleLarge, color = c.text)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        PrimaryButton("Nächster Titel", onClick = onSkip)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackInfo() {
    val ui = LocalUi.current
    val g = ui.graph
    val c = C.c
    val q by g.player.state.collectAsState()
    val data by g.store.data.collectAsState()
    val t = q.current ?: return
    val scope = rememberCoroutineScope()
    val liked = data.likes.any { it.track.key == t.key }
    val followed = data.followed.firstOrNull { f -> t.artist != null && f.name.equals(t.artist, ignoreCase = true) }
    val analysed = data.analysis[t.key] != null
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(t.title, style = MaterialTheme.typography.displayMedium, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            t.artist ?: "Unbekannter Künstler", style = MaterialTheme.typography.titleLarge, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = !t.artist.isNullOrBlank()) { ui.goToArtist(t) },
        )
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip(if (liked) PhosphorIcons.Fill.Heart else PhosphorIcons.Regular.Heart, if (liked) "Gefällt dir" else "Gefällt mir", liked) { ui.toggleLike(t) }
            if (!t.artist.isNullOrBlank()) ActionChip(if (followed != null) PhosphorIcons.Regular.UserCheck else PhosphorIcons.Regular.UserPlus, if (followed != null) "Folge ich" else "Folgen", followed != null) {
                if (followed != null) { g.store.toggleFollow(followed); ui.toast("Du folgst ${followed.name} nicht mehr") }
                else scope.launch {
                    val a = withContext(Dispatchers.IO) { runCatching { g.search.findArtistByName(t.artist!!, t.source) }.getOrNull() }
                    if (a == null) ui.toast("Künstler nicht gefunden")
                    else { g.store.toggleFollow(FollowedArtist(a.source, a.sourceId, a.name, a.thumbnailUrl)); ui.toast("Du folgst jetzt ${a.name}") }
                }
            }
            ActionChip(PhosphorIcons.Regular.ShareNetwork, "Teilen", false) { ui.copyLink(t.webpageUrl) }
            ActionChip(PhosphorIcons.Regular.PlusCircle, "Zu Playlist", false) { ui.addToPlaylist = listOf(t) }
            ActionChip(PhosphorIcons.Regular.Waveform, if (analysed) "Übergänge analysiert" else "Übergänge analysieren", analysed) { ui.analyze(listOf(t)) }
        }
    }
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val c = C.c
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (active) c.accent2.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(icon, null, tint = if (active) c.accent2 else c.text, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = c.text)
    }
}

@Composable
private fun PlayerCard(title: String, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = C.c
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.06f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.text, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        content()
    }
}

@Composable
private fun OfflineCard() {
    val ui = LocalUi.current
    val g = ui.graph
    val c = C.c
    val q by g.player.state.collectAsState()
    val data by g.store.data.collectAsState()
    val t = q.current ?: return
    val dl = data.downloads.firstOrNull { it.track.key == t.key }
    val drm = t.isDrmProtected || q.error?.contains("DRM", true) == true
    val completed = dl?.state == DownloadState.COMPLETED
    val status = when {
        q.playingLocalCopy -> "Auf dem Gerät gespeichert" + (dl?.filePath?.let { File(it).takeIf(File::isFile)?.length() }?.let { " · ${formatBytes(it)}" } ?: "")
        completed -> "Lokale Kopie verfügbar"
        drm -> "Nicht herunterladbar (DRM-geschützt)"
        dl?.state == DownloadState.DOWNLOADING -> "Wird heruntergeladen · ${dl.progressPct} %"
        dl?.state == DownloadState.QUEUED -> "Download wartet …"
        dl?.state == DownloadState.PAUSED -> "Download pausiert · ${dl.progressPct} %"
        dl?.state == DownloadState.FAILED -> "Download fehlgeschlagen"
        else -> "Zum Offline-Hören herunterladen"
    }
    PlayerCard(
        if (q.playingLocalCopy) "Offline verfügbar" else "Wird gestreamt",
        trailing = {
            Switch(
                checked = dl != null && dl.state != DownloadState.FAILED, enabled = !drm,
                onCheckedChange = { on -> if (on) ui.download(listOf(t)) else { g.downloads.remove(t.key); ui.toast("Download entfernt") } },
                colors = SwitchDefaults.colors(checkedTrackColor = c.accent),
            )
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (q.playingLocalCopy) PhosphorIcons.Regular.CheckCircle else PhosphorIcons.Regular.Cloud, null, tint = if (q.playingLocalCopy) c.accentStrong else c.textMuted, modifier = Modifier.size(18.dp))
            Text(status, style = MaterialTheme.typography.bodyMedium, color = c.textMuted, modifier = Modifier.weight(1f))
            when {
                dl?.state == DownloadState.FAILED -> TextButton({ g.downloads.resume(t.key) }) { Text("Erneut versuchen", color = c.accent2) }
                dl?.state == DownloadState.PAUSED -> TextButton({ g.downloads.resume(t.key) }) { Text("Fortsetzen", color = c.accent2) }
                completed || q.playingLocalCopy -> TextButton({ g.player.toggleSource() }) {
                    Icon(PhosphorIcons.Regular.ArrowsClockwise, null, tint = c.accent2, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (q.playingLocalCopy) "Stream verwenden" else "Lokale Kopie abspielen", color = c.accent2)
                }
            }
        }
        if (dl?.state == DownloadState.DOWNLOADING) LinearProgressIndicator(
            progress = { dl.progressPct / 100f }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)), color = c.accent2, trackColor = Color.White.copy(alpha = 0.1f),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SoundCard() {
    val ui = LocalUi.current
    val g = ui.graph
    val c = C.c
    val settings by g.settings.state.collectAsState()
    PlayerCard("Klang", trailing = { TextButton({ ui.navigate(Route.Equalizer) }) { Text("Equalizer öffnen", color = c.accent2) } }) {
        Text("Equalizer: ${settings.eq.name}" + if (!settings.eq.enabled) " (aus)" else "", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (EqProfile.PRESETS + settings.eqUserPresets).distinctBy { it.name }.forEach { p ->
                SmallPill(p.name, settings.eq.enabled && settings.eq.name == p.name) { g.settings.update { it.copy(eq = p) } }
            }
        }
        Text("3D-Sound: ${Sound3dPreset.of(settings.sound3dPreset).description}", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Sound3dPreset.entries.forEach { p ->
                SmallPill(p.label, settings.sound3dPreset == p.name) { g.settings.update { it.copy(sound3dPreset = p.name) } }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepTimerCard() {
    val g = LocalUi.current.graph
    val c = C.c
    val q by g.player.state.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(q.sleepTimerEndAt) {
        while (q.sleepTimerEndAt != null) { now = System.currentTimeMillis(); delay(1000) }
    }
    val remaining = formatSleepRemaining(q.sleepTimerEndAt, now)
    PlayerCard("Sleep-Timer", trailing = {
        if (q.sleepTimerEndAt != null) TextButton({ g.player.setSleepTimer(null) }) { Text("Timer beenden", color = c.accent2) }
    }) {
        Text(
            if (remaining != null) "Wiedergabe stoppt in $remaining" else "Pausiert die Wiedergabe nach Ablauf der Zeit.",
            style = MaterialTheme.typography.labelMedium, color = if (remaining != null) c.accent2 else c.textMuted, fontWeight = if (remaining != null) FontWeight.SemiBold else null,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SLEEP_TIMER_PRESETS.forEach { m -> SmallPill("$m Min", false) { g.player.setSleepTimer(m) } }
        }
    }
}

