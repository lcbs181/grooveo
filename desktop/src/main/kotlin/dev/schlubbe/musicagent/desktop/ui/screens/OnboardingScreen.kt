package dev.schlubbe.musicagent.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Waveform
import com.adamglin.phosphoricons.regular.ArrowRight
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.Cloud
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.X
import com.adamglin.phosphoricons.regular.YoutubeLogo
import dev.schlubbe.musicagent.data.remote.dto.ArtistResultDto
import dev.schlubbe.musicagent.desktop.APP_VERSION
import dev.schlubbe.musicagent.desktop.ui.C
import dev.schlubbe.musicagent.desktop.ui.Cover
import dev.schlubbe.musicagent.desktop.ui.LocalUi
import dev.schlubbe.musicagent.desktop.ui.PrimaryButton
import dev.schlubbe.musicagent.desktop.ui.sourceLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Same suggestion list as the Android onboarding (MusicGenres.kt). */
val ONBOARDING_GENRES: List<String> = listOf(
    "Pop", "Hip-Hop", "Rap", "Deutschrap", "R&B", "Rock", "Indie", "Elektronisch",
    "House", "Techno", "Drum & Bass", "Lo-Fi", "Chill", "Jazz", "Klassik", "Metal",
    "Latin", "K-Pop", "Afrobeats", "Schlager",
)

/** Splits a comma separated input and merges it case-insensitively into [current]. */
fun mergeArtists(current: List<String>, raw: String): List<String> {
    val out = current.toMutableList()
    raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { n -> if (out.none { it.equals(n, true) }) out += n }
    return out
}

/**
 * First-run onboarding (intro + sources, genres, favourite artists) or, with
 * [tasteOnly], the "Musikgeschmack anpassen" page opened from settings. Picks
 * write straight through to the settings, like on Android.
 */
@Composable
fun OnboardingScreen(tasteOnly: Boolean) {
    val ui = LocalUi.current
    val settingsRepo = ui.graph.settings
    val finish = {
        settingsRepo.update { it.copy(onboardingDone = true, lastSeenVersion = APP_VERSION) }
        settingsRepo.flush()
    }
    if (tasteOnly) {
        OnboardingFrame(stepCount = 0, step = 0) {
            Text("Musikgeschmack anpassen", style = MaterialTheme.typography.displayMedium, color = C.c.text)
            Text("Genres und Künstler, die Grooveo für Home-Vorschläge nutzt.", style = MaterialTheme.typography.bodyLarge, color = C.c.textMuted)
            Spacer(Modifier.height(8.dp))
            GenreGrid()
            Spacer(Modifier.height(12.dp))
            ArtistPicker()
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Fertig", PhosphorIcons.Regular.Check) { settingsRepo.flush(); ui.back() }
        }
        return
    }
    var step by remember { mutableStateOf(0) }
    val settings by settingsRepo.state.collectAsState()
    OnboardingFrame(stepCount = 3, step = step, onKeyEnter = if (step < 2) ({ step++ }) else null) {
        when (step) {
            0 -> {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(C.c.accent), contentAlignment = Alignment.Center) {
                        Icon(PhosphorIcons.Fill.Waveform, null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    Text("Grooveo", style = MaterialTheme.typography.headlineMedium, color = C.c.text)
                }
                Spacer(Modifier.height(12.dp))
                Text("Musik, die dir gehört.", style = MaterialTheme.typography.displayLarge, color = C.c.text)
                Text(
                    "Kein Konto, kein Login, kein Backend. Suche, streame und lade direkt auf deinem Rechner.",
                    style = MaterialTheme.typography.bodyLarge, color = C.c.textMuted, modifier = Modifier.widthIn(max = 520.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text("Deine Quellen", style = MaterialTheme.typography.titleMedium, color = C.c.text)
                SourceCard(PhosphorIcons.Regular.Cloud, C.c.accent, "SoundCloud", "Suche, Streams, Künstler", settings.sourceSoundCloud) { on ->
                    settingsRepo.update { it.copy(sourceSoundCloud = on, sourceYouTube = if (!on) true else it.sourceYouTube) }
                }
                SourceCard(PhosphorIcons.Regular.YoutubeLogo, C.c.accent2, "YouTube Music", "Suche, Streams, Downloads", settings.sourceYouTube) { on ->
                    settingsRepo.update { it.copy(sourceYouTube = on, sourceSoundCloud = if (!on) true else it.sourceSoundCloud) }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PrimaryButton("Weiter", PhosphorIcons.Regular.ArrowRight) { step = 1 }
                    Text("Alles bleibt lokal auf deinem Rechner.", style = MaterialTheme.typography.labelSmall, color = C.c.textFaint)
                }
            }
            1 -> {
                Text("Was hörst du gern?", style = MaterialTheme.typography.displayLarge, color = C.c.text)
                Text(
                    "Damit Grooveo dir von Anfang an etwas Passendes zeigt, statt anonymer Charts.",
                    style = MaterialTheme.typography.bodyLarge, color = C.c.textMuted, modifier = Modifier.widthIn(max = 520.dp),
                )
                Spacer(Modifier.height(8.dp))
                GenreGrid()
                Spacer(Modifier.height(16.dp))
                NavRow(onBack = { step = 0 }, onSkip = finish) { PrimaryButton("Weiter", PhosphorIcons.Regular.ArrowRight) { step = 2 } }
            }
            else -> {
                Text("Lieblingskünstler", style = MaterialTheme.typography.displayLarge, color = C.c.text)
                Text("Optional – such nach Künstlern oder tippe Namen mit Komma getrennt ein.", style = MaterialTheme.typography.bodyLarge, color = C.c.textMuted)
                Spacer(Modifier.height(8.dp))
                ArtistPicker()
                Spacer(Modifier.height(16.dp))
                val canContinue = settings.preferredGenres.isNotEmpty() || settings.preferredArtists.isNotEmpty()
                NavRow(onBack = { step = 1 }, onSkip = finish) {
                    PrimaryButton("Los geht's", PhosphorIcons.Regular.ArrowRight, accent = canContinue) {
                        if (canContinue) finish()
                    }
                }
                if (!canContinue) Text("Wähle mindestens ein Genre oder einen Künstler – oder überspringe.", style = MaterialTheme.typography.bodySmall, color = C.c.textFaint)
            }
        }
    }
}

@Composable
private fun OnboardingFrame(stepCount: Int, step: Int, onKeyEnter: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = C.c
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(0f to c.accentSoft, 0.62f to c.bg, 1f to c.bg))
            .onPreviewKeyEvent { e ->
                if (onKeyEnter != null && e.type == KeyEventType.KeyUp && e.key == Key.Enter) { onKeyEnter(); true } else false
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (stepCount > 0) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                repeat(stepCount) { i ->
                    Box(Modifier.height(6.dp).width(if (i == step) 28.dp else 10.dp).clip(RoundedCornerShape(3.dp)).background(if (i <= step) c.accent else c.divider))
                }
            }
            content()
        }
    }
}

@Composable
private fun NavRow(onBack: () -> Unit, onSkip: () -> Unit, primary: @Composable () -> Unit) {
    val c = C.c
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PrimaryButton("Zurück", accent = false, onClick = onBack)
        primary()
        Spacer(Modifier.weight(1f))
        Text(
            "Überspringen", style = MaterialTheme.typography.labelLarge, color = c.textMuted,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onSkip).padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun SourceCard(icon: ImageVector, tint: Color, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = C.c
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .clickable { onChange(!checked) }.padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.text)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreGrid() {
    val repo = LocalUi.current.graph.settings
    val settings by repo.state.collectAsState()
    val c = C.c
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ONBOARDING_GENRES.forEach { genre ->
            val on = settings.preferredGenres.any { it.equals(genre, true) }
            val hover = remember { MutableInteractionSource() }
            val hovered by hover.collectIsHoveredAsState()
            Row(
                Modifier.clip(RoundedCornerShape(50)).hoverable(hover)
                    .background(if (on) c.accent else if (hovered) c.surfaceHigh else c.surface)
                    .border(1.dp, if (on) c.accent else c.divider, RoundedCornerShape(50))
                    .clickable {
                        repo.update { s -> s.copy(preferredGenres = if (on) s.preferredGenres.filterNot { it.equals(genre, true) } else s.preferredGenres + genre) }
                    }.padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (on) Icon(PhosphorIcons.Regular.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                Text(genre, style = MaterialTheme.typography.labelLarge, color = if (on) Color.White else c.text)
            }
        }
    }
}

/** "Lieblingskünstler (optional)": live artist search with suggestions plus free-text entry. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArtistPicker() {
    val ui = LocalUi.current
    val repo = ui.graph.settings
    val settings by repo.state.collectAsState()
    val c = C.c
    var input by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<ArtistResultDto>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(input) {
        val q = input.trim()
        if (q.length < 2 || q.contains(',')) { suggestions = emptyList(); return@LaunchedEffect }
        delay(350)
        searching = true
        suggestions = withContext(Dispatchers.IO) {
            runCatching { ui.graph.search.searchArtists(q, source = settings.enabledSource, limit = 8) }.getOrDefault(emptyList())
        }.distinctBy { it.name.lowercase() }
        searching = false
    }
    fun add(raw: String) {
        repo.update { it.copy(preferredArtists = mergeArtists(it.preferredArtists, raw)) }
        input = ""
        suggestions = emptyList()
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Lieblingskünstler (optional)", style = MaterialTheme.typography.titleMedium, color = c.text)
        OutlinedTextField(
            value = input, onValueChange = { input = it }, singleLine = true,
            placeholder = { Text("z. B. Bausa, Nina Chuba", color = c.textFaint) },
            leadingIcon = { Icon(PhosphorIcons.Regular.MagnifyingGlass, null, tint = c.textMuted, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (searching) CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                else if (input.isNotBlank()) Icon(PhosphorIcons.Regular.Plus, "Hinzufügen", tint = c.accent, modifier = Modifier.size(20.dp).clickable { add(input) })
            },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = c.surface, unfocusedContainerColor = c.surface,
                focusedBorderColor = c.accent, unfocusedBorderColor = c.divider, cursorColor = c.accent,
                focusedTextColor = c.text, unfocusedTextColor = c.text,
            ),
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                if (e.key == Key.Enter && e.type == KeyEventType.KeyDown) { if (input.isNotBlank()) add(input); true }
                else if (e.key == Key.Enter) true else false
            },
        )
        if (suggestions.isNotEmpty()) Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).border(1.dp, c.divider, RoundedCornerShape(12.dp)).padding(6.dp),
        ) {
            suggestions.forEach { a ->
                val chosen = settings.preferredArtists.any { it.equals(a.name, true) }
                val hover = remember(a) { MutableInteractionSource() }
                val hovered by hover.collectIsHoveredAsState()
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).hoverable(hover).background(if (hovered) c.surfaceHigh else Color.Transparent)
                        .clickable { add(a.name) }.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Cover(a.thumbnailUrl, 36.dp, circle = true)
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(sourceLabel(a.source), a.subscriberCount).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1)
                    }
                    Icon(if (chosen) PhosphorIcons.Regular.Check else PhosphorIcons.Regular.Plus, null, tint = c.accent, modifier = Modifier.size(18.dp))
                }
            }
        }
        if (settings.preferredArtists.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            settings.preferredArtists.forEach { name ->
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(c.surface).border(1.dp, c.divider, RoundedCornerShape(50))
                        .clickable { repo.update { it.copy(preferredArtists = it.preferredArtists - name) } }
                        .padding(start = 14.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(name, style = MaterialTheme.typography.bodySmall, color = c.text)
                    Icon(PhosphorIcons.Regular.X, "$name entfernen", tint = c.textMuted, modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}
