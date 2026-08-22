package dev.schlubbe.musicagent.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.schlubbe.musicagent.data.local.entity.FollowedArtistEntity
import dev.schlubbe.musicagent.ui.components.CanopyButton
import dev.schlubbe.musicagent.ui.components.CanopyButtonVariant
import dev.schlubbe.musicagent.ui.components.CanopyIconButton
import dev.schlubbe.musicagent.ui.components.canopyCard
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.theme.accentColorFor

private data class ProfileAccentOption(val key: String?, val label: String)

private val ProfileAccentOptions = listOf(
    ProfileAccentOption(null, "Auto"),
    ProfileAccentOption("accent", "Akzent"),
    ProfileAccentOption("accent2", "Akzent 2"),
    ProfileAccentOption("neutral", "Neutral"),
)

/** Konto (account) screen: avatar (tap to edit name + accent color), stat row,
 * and a "Folge ich" avatar rail. The gear icon opens Einstellungen as a
 * separate screen, not a dialog - Konto itself is the bottom-nav destination
 * (see NavGraph's Routes.ACCOUNT), replacing the old direct-to-Settings tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    onSettingsClick: () -> Unit,
    onArtistSelected: (source: String, sourceId: String) -> Unit,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showEditSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(containerColor = Canopy.bg) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Konto", style = MaterialTheme.typography.headlineMedium)
                CanopyIconButton(
                    icon = phosphorIcon("gear-six"),
                    onClick = onSettingsClick,
                    shape = CircleShape,
                    variant = CanopyButtonVariant.Secondary,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .clickable { showEditSheet = true },
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(accentColorFor(uiState.profileColorStyle.takeIf { it != "auto" }, uiState.profileName)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(phosphorIcon("user"), contentDescription = null, tint = Canopy.neutral200, modifier = Modifier.size(26.dp))
                }
                Column(modifier = Modifier.padding(start = 14.dp)) {
                    Text(uiState.profileName.ifBlank { "Name festlegen" }, style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Lokales Profil · kein Login erforderlich",
                        style = MaterialTheme.typography.labelSmall,
                        color = Canopy.neutral500,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Icon(phosphorIcon("pencil-simple"), contentDescription = "Bearbeiten", tint = Canopy.neutral500, modifier = Modifier.size(15.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatItem(value = uiState.playlistCount, label = "Playlists", modifier = Modifier.weight(1f))
                StatItem(value = uiState.likeCount, label = "Likes", modifier = Modifier.weight(1f))
                StatItem(value = uiState.following.size, label = "Folge ich", modifier = Modifier.weight(1f))
            }

            if (uiState.statLine.isNotBlank()) {
                Text(
                    uiState.statLine,
                    style = MaterialTheme.typography.labelMedium,
                    color = Canopy.neutral500,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }

            Spacer(modifier = Modifier.padding(top = 16.dp))
            Text("Folge ich", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(modifier = Modifier.padding(top = 8.dp))
            if (uiState.following.isEmpty()) {
                Text(
                    "Du folgst noch niemandem",
                    color = Canopy.neutral500,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(uiState.following, key = { "${it.source}:${it.sourceId}" }) { artist ->
                        FollowedArtistItem(artist = artist, onClick = { onArtistSelected(artist.source, artist.sourceId) })
                    }
                }
            }
        }
    }

    if (showEditSheet) {
        ProfileEditSheet(
            initialName = uiState.profileName,
            initialColorStyle = uiState.profileColorStyle,
            onDismiss = { showEditSheet = false },
            onSave = { name, colorStyle ->
                viewModel.updateProfile(name, colorStyle)
                showEditSheet = false
            },
        )
    }
}

@Composable
private fun StatItem(value: Int, label: String, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.canopyCard().padding(vertical = 6.dp),
    ) {
        Text(value.toString(), style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Canopy.neutral500)
    }
}

@Composable
private fun FollowedArtistItem(artist: FollowedArtistEntity, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(66.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(accentColorFor(null, artist.sourceId)),
        )
        Spacer(modifier = Modifier.padding(top = 6.dp))
        Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditSheet(
    initialName: String,
    initialColorStyle: String,
    onDismiss: () -> Unit,
    onSave: (name: String, colorStyle: String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var colorStyle by remember { mutableStateOf(initialColorStyle.takeIf { it != "auto" }) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Canopy.surface) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Profil bearbeiten", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.padding(top = 12.dp))
            Text("Name", style = MaterialTheme.typography.labelMedium, color = Canopy.neutral400)
            Spacer(modifier = Modifier.padding(top = 4.dp))
            TextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Canopy.bg,
                    unfocusedContainerColor = Canopy.bg,
                    focusedIndicatorColor = Canopy.divider,
                    unfocusedIndicatorColor = Canopy.divider,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.padding(top = 16.dp))
            Text("Avatarfarbe", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ProfileAccentOptions.forEach { option ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val selected = colorStyle == option.key
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .border(2.dp, if (selected) Canopy.text else Color.Transparent, CircleShape)
                                .padding(3.dp)
                                .clip(CircleShape)
                                .background(accentColorFor(option.key, name))
                                .clickable { colorStyle = option.key },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(phosphorIcon("check"), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                        Text(option.label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(modifier = Modifier.padding(top = 20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                CanopyButton(text = "Abbrechen", onClick = onDismiss, variant = CanopyButtonVariant.Secondary)
                Spacer(modifier = Modifier.padding(start = 10.dp))
                CanopyButton(text = "Speichern", onClick = { onSave(name.trim(), colorStyle ?: "auto") })
            }
        }
    }
}
