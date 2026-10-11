package dev.schlubbe.musicagent.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.schlubbe.musicagent.data.sync.RemoteAi
import dev.schlubbe.musicagent.ui.components.CanopyButton
import dev.schlubbe.musicagent.ui.components.CanopyButtonVariant
import dev.schlubbe.musicagent.ui.components.CanopyChip
import dev.schlubbe.musicagent.ui.components.CanopyIconButton
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.theme.CanopyShapes
import dev.schlubbe.musicagent.ui.util.markdown
import kotlinx.coroutines.launch

private val SUGGESTIONS = listOf(
    "Spiel was Ähnliches wie diesen Titel",
    "Mach mir eine Playlist zum Lernen ohne Gesang",
    "Bau mir eine FX-Kette für Club-Sound",
    "Mach leiser",
)

/**
 * "KI" tab: chat with the assistant of the paired PC. It controls Grooveo on the PC
 * (playback, playlists, sound, DJ transitions); its questions are answered here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssistantScreen(onOpenSettings: () -> Unit, viewModel: AssistantViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsState()
    val remote = ui.remote
    val items = remote?.items.orEmpty()
    val running = remote?.running == true
    var input by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()

    // follow the PC only while the tab is shown
    LaunchedEffect(Unit) { launch { viewModel.followPlayer() }; viewModel.follow() }
    LaunchedEffect(items.size, items.lastOrNull()?.text?.length) {
        if (items.isNotEmpty()) list.animateScrollToItem(items.size - 1)
    }
    fun send(text: String = input) {
        if (text.isBlank() || running || remote == null) return
        viewModel.send(text)
        input = ""
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Canopy.bg)
            .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("KI", style = MaterialTheme.typography.headlineLarge, color = Canopy.text)
                Text(
                    remote?.provider?.let { "Steuert Grooveo am PC · $it" } ?: "Steuert Grooveo am PC",
                    style = MaterialTheme.typography.labelSmall,
                    color = Canopy.neutral500,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (remote != null && items.isNotEmpty()) {
                CanopyButton("Neu", onClick = viewModel::newConversation, variant = CanopyButtonVariant.Ghost)
            }
        }

        if (!ui.paired) {
            NotPaired(onOpenSettings)
            return@Column
        }

        ui.nowPlaying?.let { np -> PcPlayer(np, viewModel::playback) }
        ui.error?.let { err ->
            Text(
                err,
                style = MaterialTheme.typography.bodySmall,
                color = Canopy.accent2,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = list,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (remote == null && ui.error == null) item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Canopy.accent, strokeWidth = 2.dp)
                    Text("Verbinde mit dem PC …", style = MaterialTheme.typography.bodyMedium, color = Canopy.neutral500)
                }
            }
            if (remote != null && remote.enabled && items.isEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        "Sag Grooveo am PC, was es tun soll: Musik finden, Playlists bauen, Klang einstellen, Übergänge planen. " +
                            "Bevor es löscht, Effekte einschaltet oder die Lautstärke ändert, fragt es hier nach.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Canopy.neutral500,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SUGGESTIONS.forEach { s -> CanopyChip(s, active = false, onClick = { send(s) }) }
                    }
                }
            }
            items(items, key = { it.id }) { item -> ItemView(item, viewModel::answer) }
        }

        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp)) {
            if (remote?.canUndo == true && !running) {
                CanopyButton(
                    remote.undoFor?.let { "„$it“ rückgängig" } ?: "Letzte Antwort rückgängig",
                    onClick = viewModel::undo,
                    variant = CanopyButtonVariant.Ghost,
                    leadingIcon = phosphorIcon("arrow-counter-clockwise"),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Canopy.surface)
                    .border(1.dp, Canopy.divider, RoundedCornerShape(22.dp))
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    if (input.isEmpty()) Text("Sag Grooveo, was es tun soll …", style = MaterialTheme.typography.bodyMedium, color = Canopy.neutral500)
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Canopy.text),
                        cursorBrush = SolidColor(Canopy.accent),
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 20.dp),
                    )
                }
                if (running) {
                    CanopyIconButton(phosphorIcon("stop"), onClick = viewModel::stop, variant = CanopyButtonVariant.Secondary, contentDescription = "Anhalten")
                } else {
                    CanopyIconButton(
                        phosphorIcon("arrow-up"),
                        onClick = { send() },
                        variant = CanopyButtonVariant.Primary,
                        enabled = input.isNotBlank() && remote?.enabled == true,
                        contentDescription = "Senden",
                    )
                }
            }
        }
    }
}

@Composable
private fun NotPaired(onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp).clip(CanopyShapes.medium).background(Canopy.surface)
            .border(1.dp, Canopy.divider, CanopyShapes.medium).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(phosphorIcon("desktop"), contentDescription = null, tint = Canopy.accent, modifier = Modifier.size(28.dp))
        Text("Mit dem PC koppeln", style = MaterialTheme.typography.titleMedium, color = Canopy.text, fontWeight = FontWeight.Bold)
        Text(
            "Die KI läuft in Grooveo am PC und steuert von dort die Musik. Koppel das Handy einmal über " +
                "Einstellungen → Mit PC synchronisieren (QR-Code am PC scannen). Am PC muss „KI vom Handy“ an sein.",
            style = MaterialTheme.typography.bodyMedium,
            color = Canopy.neutral500,
        )
        CanopyButton("Zu den Einstellungen", onClick = onOpenSettings)
    }
}

@Composable
private fun PcPlayer(np: PcNowPlaying, playback: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(CanopyShapes.medium)
            .background(Canopy.surface).border(1.dp, Canopy.divider, CanopyShapes.medium)
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(phosphorIcon("desktop"), contentDescription = "Am PC", tint = Canopy.neutral500, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(np.title ?: "Am PC läuft nichts", style = MaterialTheme.typography.bodyMedium, color = Canopy.text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
            np.artist?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Canopy.neutral500, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        CanopyIconButton(phosphorIcon("skip-back"), onClick = { playback("previous") }, size = 36.dp, contentDescription = "Zurück")
        CanopyIconButton(phosphorIcon(if (np.playing) "pause" else "play", filled = true), onClick = { playback("toggle") }, size = 36.dp, contentDescription = if (np.playing) "Pause" else "Abspielen")
        CanopyIconButton(phosphorIcon("skip-forward"), onClick = { playback("next") }, size = 36.dp, contentDescription = "Weiter")
    }
}

@Composable
private fun ItemView(item: RemoteAi.Item, answer: (Long, Boolean) -> Unit) {
    when (item.type) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                item.text.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = Canopy.text,
                modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                    .background(Canopy.accent100).padding(horizontal = 12.dp, vertical = 9.dp),
            )
        }
        "reply" -> if (item.text.isNullOrEmpty() && item.streaming) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Canopy.accent, strokeWidth = 2.dp)
        } else {
            val codeBg = Canopy.accent100
            Text(remember(item.text, codeBg) { markdown(item.text.orEmpty(), codeBg) }, style = MaterialTheme.typography.bodyMedium, color = Canopy.text)
        }
        "action" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (item.status) {
                "running" -> CircularProgressIndicator(Modifier.size(14.dp), color = Canopy.accent, strokeWidth = 2.dp)
                "ok" -> Icon(phosphorIcon("check"), null, tint = Canopy.accent, modifier = Modifier.size(14.dp))
                "declined" -> Icon(phosphorIcon("prohibit"), null, tint = Canopy.neutral400, modifier = Modifier.size(14.dp))
                else -> Icon(phosphorIcon("warning-circle"), null, tint = Canopy.accent2, modifier = Modifier.size(14.dp))
            }
            Column {
                Text(
                    item.label.orEmpty() + if (item.status == "declined") " · abgelehnt" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = Canopy.neutral500,
                )
                item.detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Canopy.accent2) }
            }
        }
        "confirm" -> {
            val warn = item.kind != "FX"
            val edge = if (warn) Canopy.accent2 else Canopy.accent
            Column(
                Modifier.fillMaxWidth().clip(CanopyShapes.medium).background(if (warn) Canopy.accent2_100 else Canopy.accent100)
                    .border(1.dp, edge, CanopyShapes.medium).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(item.title.orEmpty(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Canopy.text)
                Text(item.detail.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Canopy.text)
                when (item.answer) {
                    null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CanopyButton(if (item.kind == "DELETE") "Löschen" else "Erlauben", onClick = { answer(item.id, true) })
                        CanopyButton(if (item.kind == "LOUDNESS") "Lautstärke lassen" else "Ablehnen", onClick = { answer(item.id, false) }, variant = CanopyButtonVariant.Secondary)
                    }
                    true -> Text("Erlaubt", style = MaterialTheme.typography.labelMedium, color = Canopy.accent)
                    false -> Text("Abgelehnt", style = MaterialTheme.typography.labelMedium, color = Canopy.neutral500)
                }
            }
        }
        else -> Text(item.text.orEmpty(), style = MaterialTheme.typography.bodySmall, color = if (item.error) Canopy.accent2 else Canopy.neutral500)
    }
}
