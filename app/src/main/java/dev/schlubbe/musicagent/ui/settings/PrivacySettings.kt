package dev.schlubbe.musicagent.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.schlubbe.musicagent.data.local.dao.PlayEventDao
import dev.schlubbe.musicagent.data.local.dao.PodcastDao
import dev.schlubbe.musicagent.data.local.dao.TrackDao
import dev.schlubbe.musicagent.data.remote.ProxyConfig
import dev.schlubbe.musicagent.data.repository.SearchHistoryRepository
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import dev.schlubbe.musicagent.ui.components.CanopyButton
import dev.schlubbe.musicagent.ui.components.CanopyButtonVariant
import dev.schlubbe.musicagent.ui.components.CanopyChip
import dev.schlubbe.musicagent.ui.components.CanopySectionHeader
import dev.schlubbe.musicagent.ui.components.CanopyToggle
import dev.schlubbe.musicagent.ui.theme.Canopy
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PrivacySettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    @ApplicationContext private val context: Context,
    private val searchHistory: SearchHistoryRepository,
    private val playEvents: PlayEventDao,
    private val tracks: TrackDao,
    private val podcasts: PodcastDao,
) : ViewModel() {

    val appleFallback: StateFlow<Boolean> = settings.podcastAppleFallback.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val similar: StateFlow<Boolean> = settings.podcastSimilar.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val checkNew: StateFlow<Boolean> = settings.podcastCheckNew.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val checkWifiOnly: StateFlow<Boolean> = settings.podcastCheckWifiOnly.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** What the running process uses; the proxy only changes with a restart. */
    val activeProxy: ProxyConfig = ProxyConfig.load(context)

    fun setAppleFallback(on: Boolean) { viewModelScope.launch { settings.setPodcastAppleFallback(on) } }
    val youtubeAudio: StateFlow<Boolean> = settings.youtubeAudio.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val youtubeOfficialOnly: StateFlow<Boolean> = settings.youtubeOfficialOnly.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    fun setYoutubeAudio(on: Boolean) { viewModelScope.launch { settings.setYoutubeAudio(on) } }
    fun setYoutubeOfficialOnly(on: Boolean) { viewModelScope.launch { settings.setYoutubeOfficialOnly(on) } }

    fun setSimilar(on: Boolean) { viewModelScope.launch { settings.setPodcastSimilar(on) } }
    fun setCheckNew(on: Boolean) { viewModelScope.launch { settings.setPodcastCheckNew(on) } }
    fun setCheckWifiOnly(on: Boolean) { viewModelScope.launch { settings.setPodcastCheckWifiOnly(on) } }

    /** Deletes the chosen kinds of history; likes, playlists, downloads and subscriptions stay. */
    fun clearHistory(search: Boolean, music: Boolean, podcast: Boolean) {
        viewModelScope.launch {
            if (search) searchHistory.deleteAll()
            if (music) {
                playEvents.deleteAll()
                tracks.forgetPlayed()
            }
            if (podcast) podcasts.deleteAllProgress()
            android.widget.Toast.makeText(context, "Verlauf gelöscht", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Saves the proxy and restarts the app, so no pooled connection keeps the old route. */
    fun applyProxy(config: ProxyConfig) {
        ProxyConfig.save(context, config)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launch)
        Runtime.getRuntime().exit(0)
    }
}

/** Where spoken-word audio may come from. YouTube is off until switched on here. */
@Composable
fun AudioSourcesSection(viewModel: PrivacySettingsViewModel = hiltViewModel()) {
    val youtube by viewModel.youtubeAudio.collectAsState()
    val officialOnly by viewModel.youtubeOfficialOnly.collectAsState()
    Column {
        CanopySectionHeader(title = "Audio-Quellen")
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PrivacyToggle(
                "YouTube als Audioquelle",
                "Hörspiele und Hörbücher von YouTube-Kanälen. Die Suche unter \"Hörspiele & Hörbücher\" findet dann auch " +
                    "Reihen, Alben und einzelne Videos als Audio, mit Weiterhören an der letzten Stelle.",
                youtube,
                viewModel::setYoutubeAudio,
            )
            if (youtube) {
                PrivacyToggle(
                    "Hörspiele & Hörbücher nur von offiziellen Kanälen",
                    "Verlage und Sender, die die Rechte haben. Auf YouTube ist rund die Hälfte der Hörspiel-Treffer " +
                        "ein fremder Upload, eine Fan-Abmischung oder ein Fake.",
                    officialOnly,
                    viewModel::setYoutubeOfficialOnly,
                )
            }
        }
    }
}

@Composable
fun PrivacySection(viewModel: PrivacySettingsViewModel = hiltViewModel()) {
    val apple by viewModel.appleFallback.collectAsState()
    val checkNew by viewModel.checkNew.collectAsState()
    val similar by viewModel.similar.collectAsState()
    val wifiOnly by viewModel.checkWifiOnly.collectAsState()
    val active = viewModel.activeProxy
    var mode by remember { mutableStateOf(active.mode) }
    var host by remember { mutableStateOf(active.host) }
    var port by remember { mutableStateOf(active.port.toString()) }
    val draft = ProxyConfig(mode, host.trim(), port.toIntOrNull() ?: 0)
    val changed = draft.mode != active.mode || (mode == "custom" && (draft.host != active.host || draft.port != active.port))

    Column {
        CanopySectionHeader(title = "Datenschutz")
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "Grooveo selbst sammelt nichts und hat kein Konto. Musik, Podcasts und Bilder kommen aber direkt " +
                    "von fremden Diensten (YouTube, SoundCloud, ARD, Podcast-Hoster, fyyd, ggf. Apple). " +
                    "Die sehen deine IP-Adresse und was du abrufst.",
                style = MaterialTheme.typography.bodySmall,
                color = Canopy.neutral500,
            )
            PrivacyToggle(
                "Apple für Podcast-Suche",
                "Gesucht wird zuerst bei fyyd. Nur wenn dort nichts passt, wird Apple gefragt. Aus: Apple bekommt nie eine Anfrage.",
                apple,
                viewModel::setAppleFallback,
            )
            PrivacyToggle(
                "Ähnliche Podcasts vorschlagen",
                "\"Hörtipps für dich\" werden auf dem Gerät berechnet. Für Vorschläge fragt die App fyyd nach Podcasts, die deinen zwei liebsten ähneln. Aus: fyyd erfährt nichts über deine Abos.",
                similar,
                viewModel::setSimilar,
            )
            PrivacyToggle(
                "Nach neuen Folgen suchen",
                "Fragt alle 6 Stunden die Feeds deiner Abos ab. Jeder Hoster sieht dabei deine IP-Adresse.",
                checkNew,
                viewModel::setCheckNew,
            )
            if (checkNew) {
                PrivacyToggle("Nur im WLAN", null, wifiOnly, viewModel::setCheckWifiOnly)
            }
            HistoryControls(viewModel::clearHistory)
            Column {
                Text("Verbindung über Proxy", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Leitet den Verkehr der App über Tor oder einen SOCKS5-Proxy. Dienste sehen dann nicht deine IP-Adresse. " +
                        "Für Tor muss Orbot laufen. YouTube blockiert Tor oft. Läuft der Proxy nicht, lädt nichts (kein Rückfall auf direkt).",
                    style = MaterialTheme.typography.labelSmall,
                    color = Canopy.neutral500,
                )
                Spacer(modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("off" to "Aus", "tor" to "Tor (Orbot)", "custom" to "Eigener").forEach { (m, label) ->
                        CanopyChip(label = label, active = mode == m, onClick = { mode = m })
                    }
                }
                if (mode == "custom") {
                    Spacer(modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProxyField(host, { host = it }, "Host", Modifier.weight(2f))
                        ProxyField(port, { port = it.filter(Char::isDigit).take(5) }, "Port", Modifier.weight(1f), KeyboardType.Number)
                    }
                }
                if (changed) {
                    Spacer(modifier = Modifier.padding(top = 10.dp))
                    CanopyButton(
                        text = "Übernehmen und App neu starten",
                        onClick = { viewModel.applyProxy(draft) },
                        enabled = mode != "custom" || draft.enabled,
                    )
                }
                Text(
                    "Nicht über den Proxy laufen: Spotify-/Amazon-Import (Webansicht) und das Herunterladen von Updates (Android-Downloadmanager).",
                    style = MaterialTheme.typography.labelSmall,
                    color = Canopy.neutral500,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun HistoryControls(onClear: (search: Boolean, music: Boolean, podcast: Boolean) -> Unit) {
    var asking by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(true) }
    var music by remember { mutableStateOf(true) }
    var podcast by remember { mutableStateOf(true) }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Verlauf löschen", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Suchverlauf, was du gehört hast und wo du in Podcasts stehst. Likes, Playlists, Downloads und Abos bleiben.",
                style = MaterialTheme.typography.labelSmall,
                color = Canopy.neutral500,
            )
        }
        CanopyButton(text = "Löschen", onClick = { asking = true }, variant = CanopyButtonVariant.Secondary)
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Verlauf löschen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrivacyToggle("Suchverlauf", null, search) { search = it }
                    PrivacyToggle(
                        "Hörverlauf Musik",
                        "\"Zuletzt gehört\" und was \"Für dich\" daraus lernt. Die Empfehlungen fangen neu an.",
                        music,
                    ) { music = it }
                    PrivacyToggle(
                        "Podcasts & Hörbücher",
                        "Weiterhören-Stellen und gehörte Folgen. \"Hörtipps für dich\" fangen neu an.",
                        podcast,
                    ) { podcast = it }
                    Text(
                        "Kann nicht rückgängig gemacht werden. Was schon an einen verbundenen PC gesendet wurde, bleibt dort.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Canopy.neutral500,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { asking = false; onClear(search, music, podcast) },
                    enabled = search || music || podcast,
                ) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun PrivacyToggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Canopy.neutral500)
        }
        CanopyToggle(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ProxyField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier,
    keyboard: KeyboardType = KeyboardType.Uri,
) {
    TextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        shape = RoundedCornerShape(8.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Canopy.surface,
            unfocusedContainerColor = Canopy.surface,
            focusedIndicatorColor = Canopy.divider,
            unfocusedIndicatorColor = Canopy.divider,
        ),
        modifier = modifier,
    )
}
