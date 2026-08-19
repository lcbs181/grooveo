package dev.schlubbe.musicagent.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.schlubbe.musicagent.data.remote.dto.PlaylistOutDto
import dev.schlubbe.musicagent.ui.components.TrackThumbnail

/** Dashboard-style landing screen (in the spirit of SoundCloud's own home tab): a
 * stack of horizontally-scrolling shelves rather than one flat list, built entirely
 * from data already available via existing repositories -- the personalized feed,
 * the user's playlists, local play history, and liked tracks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onTrackSelected: () -> Unit,
    onSearchClick: () -> Unit,
    onPlaylistClick: (String) -> Unit,
    onSeeAllPlaylistsClick: () -> Unit,
    onSeeAllLikesClick: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    // Reloads every time this screen is (re)composed -- including when navigating
    // back to it from the Player -- so shelves reflect likes/plays/playlist edits
    // made elsewhere, the same "refresh on return" idea SearchViewModel uses for
    // its own feed.
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Startseite") },
                actions = {
                    IconButton(onClick = onSearchClick) {
                        Icon(Icons.Filled.Search, contentDescription = "Suche")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            // Global trending charts, always populated regardless of local history --
            // shown first so a freshly installed app has something to play immediately.
            item { ShelfHeader("Charts", onSeeAll = null) }
            item {
                when {
                    uiState.isChartsLoading -> CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                    uiState.charts.isEmpty() -> EmptyShelfHint("Charts nicht verfügbar")
                    else -> LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(uiState.charts, key = { "${it.source}:${it.sourceId}" }) { track ->
                            TrackShelfItem(
                                title = track.title,
                                subtitle = track.artist ?: track.source,
                                thumbnailUrl = track.thumbnailUrl,
                                onClick = {
                                    viewModel.onChartTrackClicked(track)
                                    onTrackSelected()
                                },
                            )
                        }
                    }
                }
            }

            item { ShelfHeader("Für dich", onSeeAll = null) }
            item {
                when {
                    uiState.isFeedLoading -> CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                    uiState.feed.isEmpty() -> EmptyShelfHint("Noch keine Empfehlungen")
                    else -> LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(uiState.feed, key = { "${it.track.source}:${it.track.sourceId}" }) { item ->
                            TrackShelfItem(
                                title = item.track.title,
                                subtitle = item.track.artist ?: item.track.source,
                                thumbnailUrl = item.track.thumbnailUrl,
                                onClick = {
                                    viewModel.onFeedTrackClicked(item)
                                    onTrackSelected()
                                },
                            )
                        }
                    }
                }
            }

            item { ShelfHeader("Deine Playlists", onSeeAll = onSeeAllPlaylistsClick) }
            item {
                when {
                    uiState.isPlaylistsLoading -> CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                    uiState.playlists.isEmpty() -> EmptyShelfHint("Noch keine Playlists")
                    else -> LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(uiState.playlists, key = { it.id }) { playlist ->
                            PlaylistShelfItem(playlist = playlist, onClick = { onPlaylistClick(playlist.id) })
                        }
                    }
                }
            }

            item { ShelfHeader("Zuletzt gehört", onSeeAll = null) }
            item {
                if (uiState.recentlyPlayed.isEmpty()) {
                    EmptyShelfHint("Noch nichts gehört")
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(uiState.recentlyPlayed, key = { it.id }) { track ->
                            TrackShelfItem(
                                title = track.title,
                                subtitle = track.artist ?: track.source,
                                thumbnailUrl = track.thumbnailUrl,
                                onClick = {
                                    viewModel.onRecentlyPlayedClicked(track)
                                    onTrackSelected()
                                },
                            )
                        }
                    }
                }
            }

            item { ShelfHeader("Deine Likes", onSeeAll = onSeeAllLikesClick) }
            item {
                when {
                    uiState.isLikesLoading -> CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                    uiState.likes.isEmpty() -> EmptyShelfHint("Noch keine Likes")
                    else -> LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(uiState.likes, key = { it.track.id }) { like ->
                            TrackShelfItem(
                                title = like.track.title,
                                subtitle = like.track.artist ?: like.track.source,
                                thumbnailUrl = like.track.thumbnailUrl,
                                onClick = {
                                    viewModel.onLikeClicked(like)
                                    onTrackSelected()
                                },
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun ShelfHeader(title: String, onSeeAll: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (onSeeAll != null) {
            TextButton(onClick = onSeeAll) { Text("Alle anzeigen") }
        }
    }
}

@Composable
private fun EmptyShelfHint(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun TrackShelfItem(
    title: String,
    subtitle: String?,
    thumbnailUrl: String?,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable(onClick = onClick),
    ) {
        TrackThumbnail(thumbnailUrl, size = 120.dp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlaylistShelfItem(playlist: PlaylistOutDto, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Icon(Icons.Filled.QueueMusic, contentDescription = null)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                playlist.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "${playlist.trackCount} Titel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
