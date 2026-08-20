package dev.schlubbe.musicagent.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.schlubbe.musicagent.data.local.entity.TrackEntity
import dev.schlubbe.musicagent.data.remote.dto.PlaylistOutDto
import androidx.compose.foundation.border
import dev.schlubbe.musicagent.ui.components.EqualizerBadge
import dev.schlubbe.musicagent.ui.components.NocturneButton
import dev.schlubbe.musicagent.ui.components.NocturneIconButton
import dev.schlubbe.musicagent.ui.components.SegmentedControl
import dev.schlubbe.musicagent.ui.components.TrackThumbnail
import dev.schlubbe.musicagent.ui.components.nocturneCard
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Nocturne
import dev.schlubbe.musicagent.ui.theme.accentColorFor

/** Dashboard-style landing screen, section order matches
 * `design_handoff_music_agent_redesign/Music Agent.dc.html`'s isHome block
 * exactly: header, resume card, Mix+mood chips, Im Fokus, Charts, Neu von
 * Künstlern, Für dich, Deine Playlists, Zuletzt gehört, Deine Likes. */
@Composable
fun HomeScreen(
    onTrackSelected: () -> Unit,
    onSearchClick: () -> Unit,
    onPlaylistClick: (String) -> Unit,
    onSeeAllPlaylistsClick: () -> Unit,
    onSeeAllLikesClick: () -> Unit,
    onWhatsNewClick: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    // Reloads every time this screen is (re)composed -- including when navigating
    // back to it from the Player -- so shelves reflect likes/plays/playlist edits
    // made elsewhere, the same "refresh on return" idea SearchViewModel uses for
    // its own feed.
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(containerColor = Nocturne.bg) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column {
                        Text(uiState.greetingText, style = MaterialTheme.typography.headlineSmall)
                        if (uiState.statLine.isNotBlank()) {
                            Text(
                                uiState.statLine,
                                style = MaterialTheme.typography.labelMedium,
                                color = Nocturne.neutral500,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    NocturneIconButton(
                        icon = phosphorIcon("magnifying-glass"),
                        onClick = onSearchClick,
                        shape = CircleShape,
                        variant = dev.schlubbe.musicagent.ui.components.NocturneButtonVariant.Secondary,
                    )
                }
            }

            if (uiState.showWhatsNewBanner) {
                item {
                    WhatsNewBanner(
                        versionLabel = uiState.latestVersionLabel,
                        onOpen = {
                            viewModel.onWhatsNewBannerSeen()
                            onWhatsNewClick()
                        },
                        onDismiss = viewModel::onWhatsNewBannerSeen,
                    )
                }
            }

            uiState.resumeTrack?.let { resume ->
                item {
                    ResumeCard(
                        resume = resume,
                        onClick = onTrackSelected,
                        onPlayPauseClick = viewModel::onResumeCardClicked,
                    )
                }
            }

            if (uiState.showMixControls) {
                item {
                    MixRow(
                        isLoading = uiState.isMixLoading,
                        onMoodClicked = viewModel::onMoodChipClicked,
                    )
                }
            }

            if (uiState.showFeatured) {
                item { ShelfHeader("Im Fokus", onSeeAll = null) }
                item {
                    val featured = uiState.charts.take(6)
                    if (featured.isEmpty()) {
                        EmptyShelfHint("Noch nichts im Fokus")
                    } else {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(featured, key = { "featured:${it.source}:${it.sourceId}" }) { track ->
                                FeaturedCard(
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
            }

            item { ShelfHeader("Charts", onSeeAll = null) }
            item {
                when {
                    uiState.isChartsLoading -> CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                    uiState.charts.isEmpty() -> EmptyShelfHint("Charts nicht verfügbar")
                    else -> LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
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

            if (uiState.showNewUploads && uiState.topArtists.isNotEmpty()) {
                item { ShelfHeader("Neu von Künstlern, die du hörst", onSeeAll = null) }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(uiState.topArtists, key = { it }) { artist ->
                            ArtistAvatarItem(name = artist)
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
                        contentPadding = PaddingValues(horizontal = 20.dp),
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
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(uiState.playlists, key = { it.id }) { playlist ->
                            PlaylistShelfItem(playlist = playlist, onClick = { onPlaylistClick(playlist.id) })
                        }
                    }
                }
            }

            if (uiState.recentlyPlayed.isNotEmpty()) {
                item { ShelfHeader("Zuletzt gehört", onSeeAll = null) }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
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
                        contentPadding = PaddingValues(horizontal = 20.dp),
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

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

/** Dismissible, glowing-border card under the header pointing at the full
 * What's New screen -- a lightweight nudge rather than forcing the full-screen
 * page on every Home visit (see the design handoff's onboarding section). */
@Composable
private fun WhatsNewBanner(versionLabel: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 14.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Nocturne.accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .background(Nocturne.surface)
            .clickable(onClick = onOpen)
            .padding(13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Nocturne.accent800),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                phosphorIcon("sparkle"),
                contentDescription = null,
                tint = Nocturne.accent300,
                modifier = Modifier.size(16.dp),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 11.dp)) {
            Text("Neu in $versionLabel", style = MaterialTheme.typography.labelLarge)
            Text(
                "3D-Sound-Vorlagen, automatische Sicherungen und persönlichere Playlists.",
                style = MaterialTheme.typography.labelSmall,
                color = Nocturne.neutral500,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        NocturneIconButton(icon = phosphorIcon("x"), onClick = onDismiss, size = 26.dp, iconSize = 14.dp)
    }
}

@Composable
private fun ResumeCard(resume: ResumeTrack, onClick: () -> Unit, onPlayPauseClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 14.dp)
            .nocturneCard(padding = 10.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            TrackThumbnail(resume.artworkUrl, size = 52.dp)
            EqualizerBadge(
                isPlaying = resume.isPlaying,
                size = 15.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Weiter hören", style = MaterialTheme.typography.labelSmall, color = Nocturne.neutral500)
            Text(
                resume.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(modifier = Modifier.height(6.dp))
            val progress = if (resume.durationMs > 0) {
                (resume.positionMs.toFloat() / resume.durationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Nocturne.neutral800),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(3.dp)
                        .background(Nocturne.accent),
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        NocturneIconButton(
            icon = phosphorIcon(if (resume.isPlaying) "pause" else "play"),
            onClick = onPlayPauseClick,
        )
    }
}

@Composable
private fun MixRow(isLoading: Boolean, onMoodClicked: (MoodFilter) -> Unit) {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        NocturneButton(
            text = "Mix starten",
            onClick = { onMoodClicked(MoodFilter.ALL) },
            leadingIcon = phosphorIcon("shuffle"),
            enabled = !isLoading,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        SegmentedControl(
            options = MoodFilter.entries,
            selected = MoodFilter.ALL,
            onSelect = onMoodClicked,
            label = { it.label },
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

@Composable
private fun FeaturedCard(title: String, subtitle: String, thumbnailUrl: String?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(220.dp)
            .aspectRatio(220f / 126f)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        TrackThumbnail(
            thumbnailUrl,
            size = 220.dp,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Nocturne.bg.copy(alpha = 0.75f)),
                        startY = 0f,
                    ),
                ),
        )
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                subtitle,
                color = Nocturne.neutral300,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ShelfHeader(title: String, onSeeAll: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        if (onSeeAll != null) {
            Text(
                "Alle anzeigen",
                color = Nocturne.accent,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable(onClick = onSeeAll),
            )
        }
    }
}

@Composable
private fun EmptyShelfHint(text: String) {
    Text(
        text,
        color = Nocturne.neutral500,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
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
            .width(104.dp)
            .clickable(onClick = onClick),
    ) {
        TrackThumbnail(thumbnailUrl, size = 104.dp)
        Spacer(modifier = Modifier.height(7.dp))
        Text(
            title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = Nocturne.neutral500,
            )
        }
    }
}

@Composable
private fun PlaylistShelfItem(playlist: PlaylistOutDto, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(148.dp)
            .nocturneCard()
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(accentColorFor(playlist.accentColorKey, playlist.id)),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "PLAYLIST",
            style = MaterialTheme.typography.labelSmall,
            color = Nocturne.accent,
        )
        Text(
            playlist.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            "${playlist.trackCount} Titel",
            style = MaterialTheme.typography.labelSmall,
            color = Nocturne.neutral500,
        )
    }
}

@Composable
private fun ArtistAvatarItem(name: String) {
    Column(
        modifier = Modifier.width(66.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .border(2.dp, Nocturne.accent, CircleShape)
                .padding(2.dp)
                .clip(CircleShape)
                .background(Nocturne.accent800),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = Nocturne.accent300,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
