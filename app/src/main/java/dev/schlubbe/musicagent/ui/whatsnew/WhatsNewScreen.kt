package dev.schlubbe.musicagent.ui.whatsnew

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.schlubbe.musicagent.BuildConfig
import dev.schlubbe.musicagent.ui.components.NocturneButton
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Nocturne

private data class WhatsNewItem(val iconName: String, val title: String, val description: String)

private val ITEMS = listOf(
    WhatsNewItem(
        "vinyl-record",
        "Charts & Für dich nur noch YT Music",
        "Charts und Für-dich-Empfehlungen zeigen jetzt nur noch YouTube-Music-Titel, keine SoundCloud-Mischung mehr.",
    ),
    WhatsNewItem(
        "lock-simple",
        "DRM-Hinweis bei SoundCloud",
        "SoundCloud-Titel, die sich wegen DRM-Schutz nicht abspielen lassen, sind jetzt schon in der Liste erkennbar.",
    ),
    WhatsNewItem(
        "stack",
        "Playlist-Ansicht in der Suche",
        "Playlists und Alben aus der Suche öffnen jetzt eine eigene Übersicht mit allen Titeln, statt direkt loszuspielen.",
    ),
    WhatsNewItem(
        "heart",
        "Playlists speichern",
        "Öffentliche Playlists und Alben lassen sich jetzt speichern - zu finden in der Bibliothek unter Playlists.",
    ),
)

/** Shown after installing an update, or reachable any time via Einstellungen >
 * Updates & Sicherungen > "Was ist neu". Bottom nav is hidden by the caller
 * (see NavGraph's BOTTOM_BAR_HIDDEN_ROUTES) - immersive/onboarding-style,
 * matching the design handoff. */
@Composable
fun WhatsNewScreen(onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Nocturne.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Nocturne.accent800),
                contentAlignment = Alignment.Center,
            ) {
                Icon(phosphorIcon("sparkle"), contentDescription = null, tint = Nocturne.accent300, modifier = Modifier.size(26.dp))
            }
            Spacer(modifier = Modifier.padding(top = 16.dp))
            Text(
                "Neu in ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                "Music Agent wurde aktualisiert",
                style = MaterialTheme.typography.labelMedium,
                color = Nocturne.neutral500,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            ITEMS.forEach { item ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Nocturne.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(phosphorIcon(item.iconName), contentDescription = null, tint = Nocturne.accent, modifier = Modifier.size(18.dp))
                    }
                    Column(modifier = Modifier.padding(start = 14.dp)) {
                        Text(item.title, style = MaterialTheme.typography.labelLarge)
                        Text(
                            item.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = Nocturne.neutral500,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.padding(top = 30.dp))
        NocturneButton(text = "Los geht's", onClick = onDone, block = true)
        Spacer(modifier = Modifier.padding(bottom = 30.dp))
    }
}
