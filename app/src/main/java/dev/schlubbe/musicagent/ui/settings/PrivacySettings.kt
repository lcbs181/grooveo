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
import androidx.compose.material3.MaterialTheme
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
import dev.schlubbe.musicagent.data.remote.ProxyConfig
import dev.schlubbe.musicagent.data.repository.SettingsRepository
import dev.schlubbe.musicagent.ui.components.CanopyButton
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
) : ViewModel() {

    val appleFallback: StateFlow<Boolean> = settings.podcastAppleFallback.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val checkNew: StateFlow<Boolean> = settings.podcastCheckNew.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val checkWifiOnly: StateFlow<Boolean> = settings.podcastCheckWifiOnly.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** What the running process uses; the proxy only changes with a restart. */
    val activeProxy: ProxyConfig = ProxyConfig.load(context)

    fun setAppleFallback(on: Boolean) { viewModelScope.launch { settings.setPodcastAppleFallback(on) } }
    fun setCheckNew(on: Boolean) { viewModelScope.launch { settings.setPodcastCheckNew(on) } }
    fun setCheckWifiOnly(on: Boolean) { viewModelScope.launch { settings.setPodcastCheckWifiOnly(on) } }

    /** Saves the proxy and restarts the app, so no pooled connection keeps the old route. */
    fun applyProxy(config: ProxyConfig) {
        ProxyConfig.save(context, config)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launch)
        Runtime.getRuntime().exit(0)
    }
}

@Composable
fun PrivacySection(viewModel: PrivacySettingsViewModel = hiltViewModel()) {
    val apple by viewModel.appleFallback.collectAsState()
    val checkNew by viewModel.checkNew.collectAsState()
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
                "Nach neuen Folgen suchen",
                "Fragt alle 6 Stunden die Feeds deiner Abos ab. Jeder Hoster sieht dabei deine IP-Adresse.",
                checkNew,
                viewModel::setCheckNew,
            )
            if (checkNew) {
                PrivacyToggle("Nur im WLAN", null, wifiOnly, viewModel::setCheckWifiOnly)
            }
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
