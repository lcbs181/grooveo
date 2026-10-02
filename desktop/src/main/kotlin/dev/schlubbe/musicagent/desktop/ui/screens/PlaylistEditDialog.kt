package dev.schlubbe.musicagent.desktop.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.Image
import dev.schlubbe.musicagent.desktop.data.Playlist
import dev.schlubbe.musicagent.desktop.ui.C
import dev.schlubbe.musicagent.desktop.ui.Cover
import dev.schlubbe.musicagent.desktop.ui.Pill
import dev.schlubbe.musicagent.desktop.ui.PrimaryButton
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Same keys as the Android PlaylistEditSheet. */
val PlaylistAccentOptions: List<Pair<String?, String>> = listOf(null to "Auto", "accent" to "Akzent", "accent2" to "Akzent 2", "neutral" to "Neutral")
val PlaylistMoods: List<Pair<String, String>> = listOf("chill" to "Chill", "focus" to "Focus", "workout" to "Workout", "party" to "Party")

/** Native file picker for a cover image; returns null when cancelled. */
fun pickCoverImage(): File? {
    val d = FileDialog(null as Frame?, "Cover wählen", FileDialog.LOAD)
    d.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp") }
    d.file = "*.png;*.jpg;*.jpeg;*.webp"
    d.isVisible = true
    val f = d.file ?: return null
    return File(d.directory, f).takeIf { it.isFile }
}

/** Copies [src] into `dataDir/covers` and returns the stored path. */
fun importCover(src: File, dataDir: File, playlistId: String): String {
    val dir = File(dataDir, "covers").apply { mkdirs() }
    val dest = coverTargetFile(dir, playlistId, src.name)
    src.copyTo(dest, overwrite = true)
    return dest.absolutePath
}

/** Edit dialog: Name, Beschreibung, Farbe, Stimmung, Cover. */
@Composable
fun PlaylistEditDialog(p: Playlist, dataDir: File, onSave: (Playlist) -> Unit, onDismiss: () -> Unit) {
    val c = C.c
    var name by remember { mutableStateOf(p.name) }
    var desc by remember { mutableStateOf(p.description.orEmpty()) }
    var accent by remember { mutableStateOf(p.accentColorKey) }
    var moods by remember { mutableStateOf(p.moodTags.toSet()) }
    var coverPath by remember { mutableStateOf(p.coverPath) }
    var coverError by remember { mutableStateOf<String?>(null) }
    val save = {
        onSave(p.copy(name = name.trim().ifEmpty { p.name }, description = desc.trim().ifEmpty { null }, accentColorKey = accent, moodTags = moods.toList(), coverPath = coverPath))
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        modifier = Modifier.width(560.dp),
        title = { Text("Playlist bearbeiten", color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Cover(fileModel(coverPath) ?: p.tracks.firstOrNull()?.track?.thumbnailUrl, 112.dp, radius = 10.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton("Cover wählen", PhosphorIcons.Regular.Image, accent = false) {
                            coverError = null
                            pickCoverImage()?.let { f ->
                                runCatching { importCover(f, dataDir, p.id) }.onSuccess { coverPath = it }.onFailure { coverError = "Bild konnte nicht übernommen werden" }
                            }
                        }
                        if (coverPath != null) TextButton(onClick = { coverPath = null }) { Text("Cover entfernen", color = c.textMuted) }
                        coverError?.let { Text(it, color = c.accent2, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(desc, { desc = it }, label = { Text("Beschreibung") }, minLines = 2, maxLines = 4, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
                Text("Farbe", style = MaterialTheme.typography.titleSmall, color = c.text)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PlaylistAccentOptions.forEach { (key, label) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { accent = key }.padding(4.dp)) {
                            val col = accentFor(key, c)
                            Box(
                                Modifier.size(40.dp).clip(CircleShape)
                                    .background(if (key == null) Brush.sweepGradient(listOf(c.accent, c.accent2, c.accent)) else Brush.linearGradient(listOf(col, col)))
                                    .border(if (accent == key) 3.dp else 0.dp, if (accent == key) c.text else Color.Transparent, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { if (accent == key) Icon(PhosphorIcons.Regular.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                            Spacer(Modifier.size(4.dp))
                            Text(label, style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                        }
                    }
                }
                Text("Stimmung", style = MaterialTheme.typography.titleSmall, color = c.text)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlaylistMoods.forEach { (key, label) ->
                        Pill(label, key in moods) { moods = if (key in moods) moods - key else moods + key }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = save) { Text("Speichern", color = c.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen", color = c.textMuted) } },
    )
}
