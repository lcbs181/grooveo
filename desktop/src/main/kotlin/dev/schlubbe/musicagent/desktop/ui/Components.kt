package dev.schlubbe.musicagent.desktop.ui

import com.adamglin.phosphoricons.regular.X
import com.adamglin.phosphoricons.fill.CheckCircle
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Heart
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.regular.CaretLeft
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.CheckCircle
import com.adamglin.phosphoricons.regular.DownloadSimple
import com.adamglin.phosphoricons.regular.MusicNote
import com.adamglin.phosphoricons.regular.WarningCircle
import dev.schlubbe.musicagent.data.local.entity.DownloadState
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.desktop.data.key
import kotlinx.coroutines.launch

fun formatDuration(sec: Number?): String {
    val s = sec?.toDouble()?.takeIf { it >= 0 && !it.isNaN() }?.toLong() ?: return "–:––"
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    else -> "${b / 1024} KB"
}

fun sourceLabel(source: String) = if (source == "soundcloud") "SoundCloud" else "YouTube Music"

/**
 * Larger rendition of a SoundCloud/YouTube artwork URL for big views. Search results
 * carry list-sized images (SoundCloud t500x500, YouTube Music =w120-h120/=w544-h544,
 * i.ytimg hqdefault); the CDNs serve the same image at other sizes on request.
 * Returns [url] unchanged for anything unknown.
 */
fun hiResArtwork(url: String, px: Int = 1080): String = when {
    "sndcdn.com" in url -> url.replace(Regex("-(t\\d+x\\d+|large|crop|small|badge|tiny|mini)\\.(jpg|png)$")) { "-t${px}x$px.${it.groupValues[2]}" }
    "googleusercontent.com" in url || "ggpht.com" in url ->
        url.replace(Regex("=w\\d+-h\\d+")) { "=w$px-h$px" }.replace(Regex("=s\\d+(?=-|$)")) { "=s$px" }
    "ytimg.com" in url -> url.replace(Regex("/(default|mqdefault|hqdefault|sddefault)\\.jpg(\\?.*)?$"), "/maxresdefault.jpg")
    else -> url
}

/** Album art with a Canopy gradient placeholder. [hiRes] loads a larger rendition (falls back to [url] if missing). */
@Composable
fun Cover(url: String?, size: Dp, modifier: Modifier = Modifier, radius: Dp = 8.dp, circle: Boolean = false, hiRes: Boolean = false) {
    val c = C.c
    val shape = if (circle) CircleShape else RoundedCornerShape(radius)
    Box(
        modifier.size(size).clip(shape).background(Brush.linearGradient(listOf(c.accent, c.accentStrong.copy(alpha = 0.8f)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(PhosphorIcons.Regular.MusicNote, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(size / 3))
        if (!url.isNullOrBlank()) {
            var hiResFailed by remember(url) { mutableStateOf(false) }
            val big = hiRes && !hiResFailed && hiResArtwork(url) != url
            AsyncImage(
                model = if (big) hiResArtwork(url) else url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { if (big) hiResFailed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconBtn(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    tint: Color = C.c.textMuted,
    size: Dp = 36.dp,
    iconSize: Dp = 20.dp,
    enabled: Boolean = true,
    background: Color = Color.Transparent,
    onClick: () -> Unit,
) {
    TooltipBox(TooltipDefaults.rememberTooltipPositionProvider(), tooltip = { PlainTooltip { Text(description) } }, state = rememberTooltipState()) {
        Box(
            modifier.size(size).clip(CircleShape).background(background).clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, description, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(iconSize)) }
    }
}

@Composable
fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    val c = C.c
    Row(
        modifier.clip(RoundedCornerShape(50)).background(if (selected) c.accent else c.surfaceHigh).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = if (selected) Color.White else c.text, modifier = Modifier.size(15.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) Color.White else c.text, maxLines = 1)
    }
}

@Composable
fun PrimaryButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, accent: Boolean = true, onClick: () -> Unit) {
    val c = C.c
    Row(
        modifier.clip(RoundedCornerShape(50)).background(if (accent) c.accent else c.surfaceHigh).clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = if (accent) Color.White else c.text, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (accent) Color.White else c.text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = C.c.text)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = C.c.textMuted)
        }
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action, color = C.c.accent) }
    }
}

/** Horizontal shelf with hover scroll arrows (desktop has no swipe). */
@Composable
fun Shelf(modifier: Modifier = Modifier, state: LazyListState = rememberLazyListState(), content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(modifier.hoverable(hover)) {
        LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(end = 24.dp), content = content)
        if (hovered && state.canScrollBackward) IconBtn(PhosphorIcons.Regular.CaretLeft, "Zurück", Modifier.align(Alignment.CenterStart), tint = C.c.text, background = C.c.surface) {
            scope.launch { state.animateScrollToItem((state.firstVisibleItemIndex - 4).coerceAtLeast(0)) }
        }
        if (hovered && state.canScrollForward) IconBtn(PhosphorIcons.Regular.CaretRight, "Weiter", Modifier.align(Alignment.CenterEnd), tint = C.c.text, background = C.c.surface) {
            scope.launch { state.animateScrollToItem(state.firstVisibleItemIndex + 4) }
        }
    }
}

/** Square card for tracks, playlists, albums and mixes in shelves. */
@Composable
fun CardTile(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    width: Dp = 168.dp,
    circle: Boolean = false,
    badge: String? = null,
    onPlay: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val c = C.c
    Column(
        modifier.width(width).clip(RoundedCornerShape(12.dp)).hoverable(hover)
            .background(if (hovered) c.surfaceHigh else Color.Transparent).clickable(onClick = onClick).padding(8.dp),
    ) {
        Box {
            Cover(imageUrl, width - 16.dp, radius = 10.dp, circle = circle)
            if (badge != null) Text(
                badge, style = MaterialTheme.typography.labelSmall, color = Color.White,
                modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (onPlay != null && hovered) Box(
                Modifier.align(Alignment.BottomEnd).padding(8.dp).size(44.dp).clip(CircleShape).background(c.accent2).clickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) { Icon(PhosphorIcons.Fill.Play, "Abspielen", tint = Color.White, modifier = Modifier.size(20.dp)) }
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Standard track context menu (same actions as the Android track menu). */
@Composable
fun trackMenuItems(t: TrackResultDto, queueContext: List<TrackResultDto>? = null, extra: List<ContextMenuItem> = emptyList()): () -> List<ContextMenuItem> {
    val ui = LocalUi.current
    val g = ui.graph
    return {
        val liked = g.store.isLiked(t)
        val dl = g.store.download(t.key)
        buildList {
            add(ContextMenuItem("Abspielen") { if (queueContext != null) g.player.playQueue(queueContext, queueContext.indexOf(t)) else g.player.playTrack(t) })
            add(ContextMenuItem("Als Nächstes abspielen") { g.player.playNext(t) })
            add(ContextMenuItem("Zur Warteschlange hinzufügen") { g.player.addToQueue(listOf(t)); ui.toast("Zur Warteschlange hinzugefügt") })
            add(ContextMenuItem(if (liked) "Gefällt mir nicht mehr" else "Gefällt mir") { ui.toggleLike(t) })
            add(ContextMenuItem("Zu Playlist hinzufügen …") { ui.addToPlaylist = listOf(t) })
            if (dl?.state == DownloadState.COMPLETED) add(ContextMenuItem("Download entfernen") { g.downloads.remove(t.key) })
            else add(ContextMenuItem("Herunterladen") { ui.download(listOf(t)) })
            if (!t.artist.isNullOrBlank()) add(ContextMenuItem("Zum Künstler") { ui.goToArtist(t) })
            add(ContextMenuItem("Übergänge analysieren") { ui.analyze(listOf(t)) })
            add(ContextMenuItem("Link kopieren (Teilen)") { ui.copyLink(t.webpageUrl) })
            if (!t.artist.isNullOrBlank()) add(ContextMenuItem("Künstler seltener empfehlen") { ui.dislikeArtist(t) })
            addAll(extra)
        }
    }
}

/**
 * Track list row: double-click plays (in [queueContext] when given), right-click
 * opens the track menu, hover reveals the play affordance.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    t: TrackResultDto,
    modifier: Modifier = Modifier,
    index: Int? = null,
    queueContext: List<TrackResultDto>? = null,
    subtitle: String? = null,
    highlighted: Boolean = false,
    extraMenu: List<ContextMenuItem> = emptyList(),
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val ui = LocalUi.current
    val g = ui.graph
    val c = C.c
    val data by g.store.data.collectAsState()
    val q by g.player.state.collectAsState()
    val isCurrent = q.current?.key == t.key
    val liked = data.likes.any { it.track.key == t.key }
    val dl = data.downloads.firstOrNull { it.track.key == t.key }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val play = onClick ?: { if (queueContext != null) g.player.playQueue(queueContext, queueContext.indexOfFirst { it.key == t.key }.coerceAtLeast(0)) else g.player.playTrack(t) }
    ContextMenuArea(items = trackMenuItems(t, queueContext, extraMenu)) {
        Row(
            modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).hoverable(hover)
                .background(if (hovered || highlighted) c.surfaceHigh else Color.Transparent)
                .combinedClickable(onClick = {}, onDoubleClick = play)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (index != null) Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                if (hovered) IconBtn(PhosphorIcons.Fill.Play, "Abspielen", size = 28.dp, iconSize = 14.dp, tint = c.text, onClick = play)
                else Text("${index + 1}", style = MaterialTheme.typography.bodySmall, color = if (isCurrent) c.accent2 else c.textFaint)
            }
            Box {
                Cover(t.thumbnailUrl, 44.dp, radius = 6.dp)
                if (index == null && hovered) Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = play), contentAlignment = Alignment.Center) {
                    Icon(PhosphorIcons.Fill.Play, "Abspielen", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, style = MaterialTheme.typography.titleSmall, color = if (isCurrent) c.accent2 else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (dl?.state) {
                        DownloadState.COMPLETED -> Icon(PhosphorIcons.Regular.CheckCircle, "Offline verfügbar", tint = c.accent, modifier = Modifier.size(13.dp))
                        DownloadState.DOWNLOADING, DownloadState.QUEUED -> Icon(PhosphorIcons.Regular.DownloadSimple, "Wird heruntergeladen …", tint = c.accent2, modifier = Modifier.size(13.dp))
                        DownloadState.FAILED -> Icon(PhosphorIcons.Regular.WarningCircle, "Fehlgeschlagen", tint = c.accent2, modifier = Modifier.size(13.dp))
                        else -> {}
                    }
                    Text(subtitle ?: listOfNotNull(t.artist, sourceLabel(t.source)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (t.isDrmProtected) Text("Nicht verfügbar", style = MaterialTheme.typography.labelSmall, color = c.textFaint, modifier = Modifier.padding(horizontal = 8.dp))
            if (liked || hovered) IconBtn(PhosphorIcons.Fill.Heart, if (liked) "Gefällt mir nicht mehr" else "Gefällt mir", tint = if (liked) c.accent2 else c.textFaint, size = 30.dp, iconSize = 16.dp) { ui.toggleLike(t) }
            if (hovered) DownloadButton(t, size = 30.dp, iconSize = 16.dp, tint = c.textFaint)
            trailing?.invoke()
            Text(formatDuration(t.durationSec), style = MaterialTheme.typography.bodySmall, color = c.textFaint, modifier = Modifier.width(52.dp).padding(start = 8.dp))
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(C.c.accentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = C.c.accent, modifier = Modifier.size(30.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = C.c.text)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = C.c.textMuted)
        if (action != null && onAction != null) PrimaryButton(action, onClick = onAction)
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.c.accent) }
}

@Composable
fun ErrorBox(message: String, modifier: Modifier = Modifier, onRetry: () -> Unit) =
    EmptyState(PhosphorIcons.Regular.WarningCircle, "Das hat nicht geklappt", message, modifier, "Erneut versuchen", onRetry)

/** Rounded content card used by settings and the equalizer. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = C.c.surface, tonalElevation = 0.dp) { Box(Modifier.padding(20.dp)) { content() } }
}

/**
 * Download toggle for one track, reflecting the download state: download, progress
 * ring (click cancels), downloaded (click removes the local copy), failed (retry).
 * Disabled for DRM-protected tracks, which cannot be downloaded.
 */
@Composable
fun DownloadButton(t: TrackResultDto, size: Dp = 36.dp, iconSize: Dp = 20.dp, tint: Color = C.c.textMuted) {
    val ui = LocalUi.current
    val g = ui.graph
    val c = C.c
    val data by g.store.data.collectAsState()
    val dl = data.downloads.firstOrNull { it.track.key == t.key }
    when {
        t.isDrmProtected -> IconBtn(PhosphorIcons.Regular.DownloadSimple, "Nicht herunterladbar (DRM-geschützt)", size = size, iconSize = iconSize, tint = tint, enabled = false) {}
        dl?.state == DownloadState.COMPLETED ->
            IconBtn(PhosphorIcons.Fill.CheckCircle, "Heruntergeladen – Download entfernen", size = size, iconSize = iconSize, tint = c.accent) {
                g.downloads.remove(t.key); ui.toast("Download entfernt")
            }
        dl?.state == DownloadState.DOWNLOADING || dl?.state == DownloadState.QUEUED || dl?.state == DownloadState.PAUSED ->
            Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                val pct = dl.progressPct.coerceIn(0, 100)
                if (dl.state == DownloadState.QUEUED || pct == 0) CircularProgressIndicator(Modifier.size(iconSize), color = c.accent2, strokeWidth = 2.dp)
                else CircularProgressIndicator({ pct / 100f }, Modifier.size(iconSize), color = c.accent2, strokeWidth = 2.dp, trackColor = c.textFaint.copy(alpha = 0.3f))
                IconBtn(PhosphorIcons.Regular.X, if (dl.state == DownloadState.QUEUED) "Download wartet – abbrechen" else "Wird heruntergeladen ($pct %) – abbrechen", size = size, iconSize = iconSize * 0.5f, tint = tint) {
                    g.downloads.remove(t.key); ui.toast("Download abgebrochen")
                }
            }
        dl?.state == DownloadState.FAILED ->
            IconBtn(PhosphorIcons.Regular.WarningCircle, "Download fehlgeschlagen – erneut versuchen", size = size, iconSize = iconSize, tint = c.accent2) { ui.download(listOf(t)) }
        else -> IconBtn(PhosphorIcons.Regular.DownloadSimple, "Herunterladen", size = size, iconSize = iconSize, tint = tint) { ui.download(listOf(t)) }
    }
}
