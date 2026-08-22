package dev.schlubbe.musicagent.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.schlubbe.musicagent.ui.components.CanopyButton
import dev.schlubbe.musicagent.ui.components.CanopyToggle
import dev.schlubbe.musicagent.ui.components.WaveformLogoBadge
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.theme.CanopyShapes

/** First-run screen, rebuilt on Canopy (see design_handoff_grooveo's
 * GrooveoApp.dc.html, `isOnboarding` block). Replaces the previous multi-step
 * intro animation + 4-slide tutorial with the single decision screen the new
 * design specifies: pick your sources and data-saver preference, then in.
 *
 * The three toggles are real settings, not decoration -- they write through to
 * DataStore as they're flipped and the source pair drives
 * SettingsRepository.enabledSource, which the search/charts calls honor. */
@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel, onFinished: () -> Unit) {
    val soundCloud by viewModel.soundCloudEnabled.collectAsState()
    val ytMusic by viewModel.ytMusicEnabled.collectAsState()
    val dataSaver by viewModel.dataSaverEnabled.collectAsState()

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
    ) {
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
            SourceCard(
                icon = phosphorIcon("cell-signal-slash"),
                iconTint = Canopy.neutral500,
                title = "Datensparmodus",
                subtitle = "Nur heruntergeladene Titel abspielen",
                checked = dataSaver,
                onCheckedChange = viewModel::setDataSaverEnabled,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CanopyButton(
                text = "Los geht's",
                onClick = onFinished,
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
