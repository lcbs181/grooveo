package dev.schlubbe.musicagent.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.schlubbe.musicagent.ui.components.CanopyButton
import dev.schlubbe.musicagent.ui.components.CanopyButtonVariant
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.theme.accentColorFor

enum class MoodTag(val key: String, val label: String) {
    CHILL("chill", "Chill"),
    FOCUS("focus", "Focus"),
    WORKOUT("workout", "Workout"),
    PARTY("party", "Party"),
}

private data class AccentOption(val key: String?, val label: String)

private val AccentOptions = listOf(
    AccentOption(null, "Auto"),
    AccentOption("accent", "Akzent"),
    AccentOption("accent2", "Akzent 2"),
    AccentOption("neutral", "Neutral"),
)

/** Bottom sheet for creating/editing a playlist's full metadata: name,
 * description, one of 4 accent-color swatches, and a multi-select set of mood
 * tags -- the single entry point for all of it, replacing the old plain
 * rename-only dialog (see PlaylistDetailScreen's edit-pencil icon). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistEditSheet(
    playlistId: String,
    initialName: String,
    initialDescription: String?,
    initialAccentColorKey: String?,
    initialMoodTags: List<String>,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String?, accentColorKey: String?, moodTags: List<String>) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription ?: "") }
    var accentColorKey by remember { mutableStateOf(initialAccentColorKey) }
    var selectedTags by remember { mutableStateOf(initialMoodTags.toSet()) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Canopy.surface) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Playlist bearbeiten", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.padding(top = 12.dp))

            CanopyTextField(value = name, onValueChange = { name = it }, label = "Name")
            Spacer(modifier = Modifier.padding(top = 12.dp))
            CanopyTextField(
                value = description,
                onValueChange = { description = it },
                label = "Beschreibung",
                minLines = 2,
                maxLines = 4,
            )

            Spacer(modifier = Modifier.padding(top = 16.dp))
            Text("Farbe", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AccentOptions.forEach { option ->
                    AccentSwatch(
                        color = accentColorFor(option.key, playlistId),
                        selected = accentColorKey == option.key,
                        label = option.label,
                        onClick = { accentColorKey = option.key },
                    )
                }
            }

            Spacer(modifier = Modifier.padding(top = 16.dp))
            Text("Stimmung", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoodTag.entries.forEach { tag ->
                    val selected = tag.key in selectedTags
                    MoodTagChip(
                        label = tag.label,
                        selected = selected,
                        onClick = {
                            selectedTags = if (selected) selectedTags - tag.key else selectedTags + tag.key
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.padding(top = 20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                CanopyButton(text = "Abbrechen", onClick = onDismiss, variant = CanopyButtonVariant.Secondary)
                Spacer(modifier = Modifier.padding(start = 10.dp))
                CanopyButton(
                    text = "Speichern",
                    onClick = { onSave(name.trim(), description.trim(), accentColorKey, selectedTags.toList()) },
                    enabled = name.isNotBlank(),
                )
            }
        }
    }
}

@Composable
private fun CanopyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    minLines: Int = 1,
    maxLines: Int = 1,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Canopy.neutral400)
        Spacer(modifier = Modifier.padding(top = 4.dp))
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = minLines == 1,
            minLines = minLines,
            maxLines = maxLines,
            shape = RoundedCornerShape(8.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Canopy.bg,
                unfocusedContainerColor = Canopy.bg,
                focusedIndicatorColor = Canopy.divider,
                unfocusedIndicatorColor = Canopy.divider,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MoodTagChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .let { if (selected) it.border(1.dp, Canopy.accent, RoundedCornerShape(6.dp)) else it }
            .background(if (selected) Color.Transparent else Canopy.neutral800)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Canopy.accent else Canopy.neutral100,
        )
    }
}

@Composable
private fun AccentSwatch(color: Color, selected: Boolean, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.selectable(selected = selected, onClick = onClick),
    ) {
        val ringColor = if (selected) Canopy.text else Color.Transparent
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .border(2.dp, ringColor, CircleShape)
                .padding(3.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(phosphorIcon("check"), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
