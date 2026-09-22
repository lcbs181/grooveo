package dev.schlubbe.musicagent.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.schlubbe.musicagent.ui.components.CanopyButton
import dev.schlubbe.musicagent.ui.components.CanopyChip
import dev.schlubbe.musicagent.ui.components.CanopyIconButton
import dev.schlubbe.musicagent.ui.components.CanopyToggle
import dev.schlubbe.musicagent.ui.components.WaveformLogoBadge
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.theme.CanopyPillShape
import dev.schlubbe.musicagent.ui.theme.CanopyShapes

/** First-run screen, rebuilt on Canopy (see design_handoff_grooveo's
 * GrooveoApp.dc.html, `isOnboarding` block). Two steps: pick your sources
 * (step 1), then what you're into (step 2, "Was hörst du gern?") -- the design
 * only specified the single source-picker screen, but shipping without ever
 * asking a new user's taste left every Home shelf with nothing to personalize
 * from until real history built up, so step 2 seeds that up front.
 *
 * The source toggles are real settings, not decoration -- they write through to
 * DataStore as they're flipped and the source pair drives
 * SettingsRepository.enabledSource, which the search/charts calls honor. Step
 * 2's picks write through the same way (see OnboardingViewModel). */
@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel, onFinished: () -> Unit) {
    var step by remember { mutableStateOf(0) }

    if (step == 0) {
        SourceStep(viewModel = viewModel, onNext = { step = 1 })
    } else {
        TasteStep(viewModel = viewModel, onFinished = onFinished)
    }
}

@Composable
private fun SourceStep(viewModel: OnboardingViewModel, onNext: () -> Unit) {
    val soundCloud by viewModel.soundCloudEnabled.collectAsState()
    val ytMusic by viewModel.ytMusicEnabled.collectAsState()

    OnboardingScaffold {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WaveformLogoBadge(size = 64.dp)
            Text(
                text = "Grooveo",
                style = MaterialTheme.typography.headlineSmall,
                color = Canopy.text,
            )
        }

        Column(
            modifier = Modifier.padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Musik, die dir gehört.",
                style = MaterialTheme.typography.displayLarge,
                color = Canopy.text,
                modifier = Modifier.widthIn(max = 280.dp),
            )
            Text(
                text = "Kein Konto, kein Login, kein Backend. Suche, streame und lade direkt auf dem Gerät.",
                style = MaterialTheme.typography.bodyLarge,
                color = Canopy.neutral600,
                modifier = Modifier.widthIn(max = 300.dp),
            )
        }

        Column(
            modifier = Modifier.padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SourceCard(
                icon = phosphorIcon("cloud", filled = true),
                iconTint = Canopy.accent,
                title = "SoundCloud",
                subtitle = "Suche, Streams, Künstler",
                checked = soundCloud,
                onCheckedChange = viewModel::setSoundCloudEnabled,
            )
            SourceCard(
                icon = phosphorIcon("youtube-logo", filled = true),
                iconTint = Canopy.accent2,
                title = "YouTube Music",
                subtitle = "Suche, Streams, Downloads",
                checked = ytMusic,
                onCheckedChange = viewModel::setYtMusicEnabled,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CanopyButton(
                text = "Weiter",
                onClick = onNext,
                block = true,
                trailingIcon = phosphorIcon("arrow-right"),
            )
            Text(
                text = "Alles bleibt lokal auf deinem Telefon.",
                style = MaterialTheme.typography.labelSmall,
                color = Canopy.neutral500,
            )
        }
    }
}

/** Step 2: genre chip grid plus an optional free-text artist field, each pick
 * writing straight to [OnboardingViewModel]. At least one genre or artist is
 * required to press "Los geht's" -- "Überspringen" is the deliberate escape
 * hatch for a user who'd rather not say, finishing onboarding with nothing
 * picked (Home's shelves then fall back to their pre-existing, unpersonalized
 * behaviour, same as before this screen existed). */
@Composable
private fun TasteStep(viewModel: OnboardingViewModel, onFinished: () -> Unit) {
    val genres by viewModel.preferredGenres.collectAsState()
    val artists by viewModel.preferredArtists.collectAsState()
    val canContinue = genres.isNotEmpty() || artists.isNotEmpty()

    OnboardingScaffold {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Was hörst du gern?",
                style = MaterialTheme.typography.displayLarge,
                color = Canopy.text,
                modifier = Modifier.widthIn(max = 280.dp),
            )
            Text(
                text = "Damit Grooveo dir von Anfang an etwas Passendes zeigt, statt anonymer Charts.",
                style = MaterialTheme.typography.bodyLarge,
                color = Canopy.neutral600,
                modifier = Modifier.widthIn(max = 300.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                ONBOARDING_GENRES.forEach { genre ->
                    CanopyChip(
                        label = genre,
                        active = genre in genres,
                        onClick = { viewModel.toggleGenre(genre) },
                    )
                }
            }

            ArtistPicker(
                artists = artists,
                onAdd = viewModel::addArtists,
                onRemove = viewModel::removeArtist,
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CanopyButton(
                text = "Los geht's",
                onClick = onFinished,
                block = true,
                enabled = canContinue,
                trailingIcon = phosphorIcon("arrow-right"),
            )
            Text(
                text = "Überspringen",
                style = MaterialTheme.typography.labelMedium,
                color = Canopy.neutral500,
                modifier = Modifier
                    .clip(CanopyPillShape)
                    .clickable(onClick = onFinished)
                    .padding(8.dp),
            )
        }
    }
}

/** Settings' "Musikgeschmack anpassen" entry -- the same genre/artist picker as
 * onboarding's step 2, reused verbatim (same [OnboardingViewModel], same picks),
 * but as a plain back-navigable screen rather than a first-run step: no forced
 * minimum selection, no "Los geht's"/"Überspringen" pair, just "Fertig". */
@Composable
fun TastePickerScreen(onNavigateBack: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val genres by viewModel.preferredGenres.collectAsState()
    val artists by viewModel.preferredArtists.collectAsState()

    Scaffold(containerColor = Canopy.bg) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CanopyIconButton(icon = phosphorIcon("caret-left"), onClick = onNavigateBack, iconSize = 20.dp)
                Text(
                    "Musikgeschmack",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Text(
                "Genres und Künstler, die Grooveo für Home-Vorschläge nutzt.",
                style = MaterialTheme.typography.bodySmall,
                color = Canopy.neutral500,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )

            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ONBOARDING_GENRES.forEach { genre ->
                        CanopyChip(
                            label = genre,
                            active = genre in genres,
                            onClick = { viewModel.toggleGenre(genre) },
                        )
                    }
                }

                ArtistPicker(artists = artists, onAdd = viewModel::addArtists, onRemove = viewModel::removeArtist)
            }

            CanopyButton(
                text = "Fertig",
                onClick = onNavigateBack,
                block = true,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }
}

/** "Lieblingskünstler (optional)" -- a plain text field that turns a submitted,
 * comma-separated value into removable chips rather than staying a single free
 * text blob, so it reads (and un-picks) the same way the genre grid does.
 * Submits on the keyboard's Done action or the trailing plus icon, whichever
 * the user reaches for. */
@Composable
private fun ArtistPicker(
    artists: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    fun submit() {
        if (input.isNotBlank()) onAdd(input)
        input = ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Lieblingskünstler (optional)",
            style = MaterialTheme.typography.titleSmall,
            color = Canopy.neutral600,
        )
        TextField(
            value = input,
            onValueChange = { input = it },
            placeholder = { Text("z. B. Bausa, Nina Chuba", color = Canopy.neutral500) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Canopy.surface,
                unfocusedContainerColor = Canopy.surface,
                focusedIndicatorColor = Canopy.divider,
                unfocusedIndicatorColor = Canopy.divider,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            trailingIcon = {
                Icon(
                    phosphorIcon("plus"),
                    contentDescription = "Hinzufügen",
                    tint = Canopy.accent,
                    modifier = Modifier.size(20.dp).clickable(onClick = ::submit),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (artists.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                artists.forEach { artist ->
                    RemovableChip(label = artist, onRemove = { onRemove(artist) })
                }
            }
        }
    }
}

/** Same pill shape/border as [CanopyChip]'s inactive state, plus a trailing "x"
 * -- the whole pill is the tap target for removal, matching CanopyChip's own
 * whole-pill-is-clickable behaviour rather than only the small glyph. */
@Composable
private fun RemovableChip(label: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(CanopyPillShape)
            .background(Canopy.surface)
            .border(1.dp, Canopy.divider, CanopyPillShape)
            .clickable(onClick = onRemove)
            .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = Canopy.text, style = MaterialTheme.typography.bodySmall)
        Icon(
            phosphorIcon("x"),
            contentDescription = "$label entfernen",
            tint = Canopy.neutral500,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** Shared gradient background + edge padding both onboarding steps sit in. */
@Composable
private fun OnboardingScaffold(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                // linear-gradient(180deg, accent-200 0%, bg 62%)
                Brush.verticalGradient(
                    colorStops = arrayOf(0f to Canopy.accent200, 0.62f to Canopy.bg, 1f to Canopy.bg),
                ),
            )
            .padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        content = content,
    )
}

@Composable
private fun SourceCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CanopyShapes.medium)
            .background(Canopy.surface)
            .border(1.dp, Canopy.divider, CanopyShapes.medium)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Canopy.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Canopy.neutral500,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CanopyToggle(checked = checked, onCheckedChange = onCheckedChange)
    }
}
