package dev.schlubbe.musicagent.ui.home

import android.widget.Toast
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import dev.schlubbe.musicagent.data.local.entity.TrackEntity
import dev.schlubbe.musicagent.data.remote.dto.PlaylistOutDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.data.remote.dto.toTrackResultDto
import dev.schlubbe.musicagent.ui.components.EqualizerBadge
import dev.schlubbe.musicagent.ui.components.CanopyButton
import dev.schlubbe.musicagent.ui.components.CanopyButtonVariant
import dev.schlubbe.musicagent.ui.components.CanopyIconButton
import dev.schlubbe.musicagent.ui.components.CanopyBadge
import dev.schlubbe.musicagent.ui.components.CanopyBadgeTone
import dev.schlubbe.musicagent.ui.components.CanopyBadgeVariant
import dev.schlubbe.musicagent.ui.components.SegmentedControl
import dev.schlubbe.musicagent.ui.components.TrackThumbnail
import dev.schlubbe.musicagent.ui.components.canopyCard
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.playlist.AddToPlaylistDialog
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.theme.accentColorFor
import dev.schlubbe.musicagent.ui.util.rememberResponsiveDimens
import dev.schlubbe.musicagent.ui.util.shareText

/** Local counterpart to [dev.schlubbe.musicagent.data.remote.dto.toTrackResultDto]
 * (TrackOutDto's version, already imported above for the Likes shelf) - the "Zuletzt
 * gehört" shelf's items are cached [TrackEntity] rows instead, which need the same
 * conversion to hand off to the per-track overflow menu's playlist/queue/download
 * actions (all of which take a [TrackResultDto]). Mirrors HomeViewModel's own
 * private copy of this exact mapping. */
private fun TrackEntity.toTrackResultDto(): TrackResultDto = TrackResultDto(
    source = source,
    sourceId = sourceId,
    title = title,
    artist = artist,
    album = album,
    durationSec = durationSec,
    thumbnailUrl = thumbnailUrl,
    webpageUrl = webpageUrl,
    genre = genre,
)

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
    // Defaults to a no-op: the ARTIST_DETAIL route already exists (see
    // ui.navigation.NavGraph.Routes.artistDetail), but wiring this call site's
    // MusicAgentNavGraph composable() block is outside this change's file scope.
    // Whoever owns NavGraph.kt just needs to add one line, the same lambda every
    // other screen already receives:
    // onArtistClick = { source, sourceId -> navController.navigate(Routes.artistDetail(source, sourceId)) }
    onArtistClick: (source: String, sourceId: String) -> Unit = { _, _ -> },
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val dimens = rememberResponsiveDimens()

    // Reloads every time this screen is (re)composed -- including when navigating
    // back to it from the Player -- so shelves reflect likes/plays/playlist edits
    // made elsewhere, the same "refresh on return" idea SearchViewModel uses for
    // its own feed.
    LaunchedEffect(Unit) { viewModel.refresh() }

    // "Zum Künstler" (any track's overflow menu) and the "Neu von Künstlern" avatar
    // rail both resolve here - same one-shot nav-target pattern SearchViewModel uses.
    LaunchedEffect(uiState.artistNavTarget) {
        uiState.artistNavTarget?.let { (source, sourceId) ->
            onArtistClick(source, sourceId)
            viewModel.onArtistNavigated()
        }
    }
    LaunchedEffect(uiState.artistLookupError) {
        uiState.artistLookupError?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.onArtistLookupErrorShown()
        }
    }
    LaunchedEffect(uiState.downloadMessage) {
        uiState.downloadMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.onDownloadMessageShown()
        }
    }

    uiState.trackPendingPlaylistAdd?.let {
        AddToPlaylistDialog(
            playlists = uiState.playlists,
            onDismiss = viewModel::dismissAddToPlaylist,
            onPlaylistPicked = viewModel::onPlaylistPicked,
            onCreatePlaylist = viewModel::onCreatePlaylistAndAdd,
        )
    }

    Scaffold(containerColor = Canopy.bg) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = dimens.horizontalPadding, end = dimens.horizontalPadding, top = 22.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column {
                        Text(uiState.greetingText, style = MaterialTheme.typography.headlineSmall)
                        if (uiState.statLine.isNotBlank()) {
                            Text(
                                uiState.statLine,
                                style = MaterialTheme.typography.labelMedium,
                                color = Canopy.neutral500,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    CanopyIconButton(
                        icon = phosphorIcon("magnifying-glass"),
                        onClick = onSearchClick,
                        shape = CircleShape,
                        variant = CanopyButtonVariant.Secondary,
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

            // "Weiter hören" 2x2 grid - distinct from the single big resume card
            // above (that stays the one in-progress-track card); this reuses the
            // exact same recently-played data source as the "Zuletzt gehört" shelf
            // further down (and Library's own rail), just as a compact tile grid.
            if (uiState.recentlyPlayed.isNotEmpty()) {
                item {
                    ContinueGrid(
                        tracks = uiState.recentlyPlayed.take(4),
                        onClick = { track ->
                            viewModel.onRecentlyPlayedClicked(track)
                            onTrackSelected()
                        },
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

            // "Deine Mixes" - 3 auto-generated mood mixes; this is where the
            // algorithmic personalized pool now lives (moved out of "Im Fokus",
            // which goes back to pure editorial content below).
            if (uiState.mixCards.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) {
                        Text(
                            "Deine Mixes",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = dimens.horizontalPadding).padding(bottom = 10.dp),
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = dimens.horizontalPadding),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(uiState.mixCards, key = { "mix:${it.badge}" }) { mix ->
                                MixMoodCard(
                                    mix = mix,
                                    onClick = {
                                        viewModel.onMixCardClicked(mix)
                                        onTrackSelected()
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (uiState.showFeatured) {
                item { ShelfHeader("Im Fokus", onSeeAll = null) }
                item {
                    if (uiState.featuredItems.isEmpty()) {
                        EmptyShelfHint("Noch nichts im Fokus")
                    } else {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = dimens.horizontalPadding),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(
                                uiState.featuredItems,
                                key = { "featured:${it.source}:${it.sourceId}" },
                            ) { track ->
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
                        contentPadding = PaddingValues(horizontal = dimens.horizontalPadding),
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
                                overflowMenu = {
                                    TrackOverflowMenu(
                                        track = track,
                                        onAddToPlaylistClick = { viewModel.onAddToPlaylistClicked(track) },
                                        onAddToQueueClick = { viewModel.onAddToQueueClicked(track) },
                                        onDownloadClick = { viewModel.onDownloadClicked(track) },
                                        onArtistClick = { viewModel.onTrackArtistClicked(track) },
                                    )
                                },
                            )
                        }
                    }
                }
            }

            // "Empfehlung des Tages" - single editorial/algorithmic spotlight pick,
            // stable for the whole day (see HomeViewModel.updateDailyPick).
            uiState.dailyPick?.let { pick ->
                item {
                    DailyPickCard(
                        track = pick,
                        onClick = {
                            viewModel.onDailyPickClicked()
                            onTrackSelected()
                        },
                    )
                }
            }

            if (uiState.showNewUploads && uiState.topArtists.isNotEmpty()) {
                item { ShelfHeader("Neu von Künstlern, die du hörst", onSeeAll = null) }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = dimens.horizontalPadding),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(uiState.topArtists, key = { "${it.source}:${it.sourceId ?: it.name}" }) { artist ->
                            ArtistAvatarItem(
                                artist = artist,
                                onClick = { viewModel.onArtistAvatarClicked(artist) },
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
                        contentPadding = PaddingValues(horizontal = dimens.horizontalPadding),
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
                                overflowMenu = {
                                    TrackOverflowMenu(
                                        track = item.track,
                                        onAddToPlaylistClick = { viewModel.onAddToPlaylistClicked(item.track) },
                                        onAddToQueueClick = { viewModel.onAddToQueueClicked(item.track) },
                                        onDownloadClick = { viewModel.onDownloadClicked(item.track) },
                                        onArtistClick = { viewModel.onTrackArtistClicked(item.track) },
                                    )
                                },
                            )
                        }
                    }
                }
            }

            // "Sender entdecken" - artist-seeded station tiles, reusing the exact
            // same TopArtist pool "Neu von Künstlern" already resolved above.
            if (uiState.topArtists.isNotEmpty()) {
                item { ShelfHeader("Sender entdecken", onSeeAll = null) }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = dimens.horizontalPadding),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(
                            uiState.topArtists.take(5),
                            key = { "station:${it.source}:${it.sourceId ?: it.name}" },
                        ) { artist ->
                            StationItem(
                                artist = artist,
                                onClick = {
                                    viewModel.onStationClicked(artist)
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
                        contentPadding = PaddingValues(horizontal = dimens.horizontalPadding),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(uiState.playlists, key = { it.id }) { playlist ->
                            PlaylistShelfItem(
                                playlist = playlist,
                                onClick = { onPlaylistClick(playlist.id) },
                                onDownloadClick = { viewModel.onDownloadPlaylistClicked(playlist.id) },
                                onAddToQueueClick = { viewModel.onAddPlaylistToQueueClicked(playlist.id) },
                                onDeleteClick = { viewModel.onDeletePlaylistClicked(playlist.id) },
                            )
                        }
                    }
                }
            }

            if (uiState.recentlyPlayed.isNotEmpty()) {
                item { ShelfHeader("Zuletzt gehört", onSeeAll = null) }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = dimens.horizontalPadding),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(uiState.recentlyPlayed, key = { it.id }) { track ->
                            val trackDto = track.toTrackResultDto()
                            TrackShelfItem(
                                title = track.title,
                                subtitle = track.artist ?: track.source,
                                thumbnailUrl = track.thumbnailUrl,
                                onClick = {
                                    viewModel.onRecentlyPlayedClicked(track)
                                    onTrackSelected()
                                },
                                overflowMenu = {
                                    TrackOverflowMenu(
                                        track = trackDto,
                                        onAddToPlaylistClick = { viewModel.onAddToPlaylistClicked(trackDto) },
                                        onAddToQueueClick = { viewModel.onAddToQueueClicked(trackDto) },
                                        onDownloadClick = { viewModel.onDownloadClicked(trackDto) },
                                        onArtistClick = { viewModel.onTrackArtistClicked(trackDto) },
                                    )
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
                        contentPadding = PaddingValues(horizontal = dimens.horizontalPadding),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(uiState.likes, key = { it.track.id }) { like ->
                            val trackDto = like.track.toTrackResultDto()
                            TrackShelfItem(
                                title = like.track.title,
                                subtitle = like.track.artist ?: like.track.source,
                                thumbnailUrl = like.track.thumbnailUrl,
                                onClick = {
                                    viewModel.onLikeClicked(like)
                                    onTrackSelected()
                                },
                                overflowMenu = {
                                    TrackOverflowMenu(
                                        track = trackDto,
                                        onAddToPlaylistClick = { viewModel.onAddToPlaylistClicked(trackDto) },
                                        onAddToQueueClick = { viewModel.onAddToQueueClicked(trackDto) },
                                        onDownloadClick = { viewModel.onDownloadClicked(trackDto) },
                                        onArtistClick = { viewModel.onTrackArtistClicked(trackDto) },
                                        onUnlikeClick = { viewModel.onUnlikeClicked(like) },
                                    )
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
    val dimens = rememberResponsiveDimens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.horizontalPadding)
            .padding(top = 14.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Canopy.accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .background(Canopy.surface)
            .clickable(onClick = onOpen)
            .padding(13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Canopy.accent800),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                phosphorIcon("sparkle"),
                contentDescription = null,
                tint = Canopy.accent300,
                modifier = Modifier.size(16.dp),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 11.dp)) {
            Text("Neu in $versionLabel", style = MaterialTheme.typography.labelLarge)
            Text(
                "3D-Sound-Vorlagen, automatische Sicherungen und persönlichere Playlists.",
                style = MaterialTheme.typography.labelSmall,
                color = Canopy.neutral500,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        CanopyIconButton(icon = phosphorIcon("x"), onClick = onDismiss, size = 26.dp, iconSize = 14.dp)
    }
}

@Composable
private fun ResumeCard(resume: ResumeTrack, onClick: () -> Unit, onPlayPauseClick: () -> Unit) {
    val dimens = rememberResponsiveDimens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.horizontalPadding)
            .padding(top = 14.dp)
            .canopyCard(padding = 10.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            TrackThumbnail(resume.artworkUrl, size = dimens.resumeThumbnail)
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
            Text(resume.statusLabel, style = MaterialTheme.typography.labelSmall, color = Canopy.neutral500)
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
                    .background(Canopy.neutral800),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(3.dp)
                        .background(Canopy.accent),
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        CanopyIconButton(
            icon = phosphorIcon(if (resume.isPlaying) "pause" else "play"),
            onClick = onPlayPauseClick,
        )
    }
}

@Composable
private fun MixRow(isLoading: Boolean, onMoodClicked: (MoodFilter) -> Unit) {
    val dimens = rememberResponsiveDimens()
    Column(modifier = Modifier.padding(top = 16.dp)) {
        CanopyButton(
            text = "Mix starten",
            onClick = { onMoodClicked(MoodFilter.ALL) },
            leadingIcon = phosphorIcon("shuffle"),
            enabled = !isLoading,
            modifier = Modifier.padding(horizontal = dimens.horizontalPadding),
        )
        Spacer(modifier = Modifier.height(12.dp))
        SegmentedControl(
            options = MoodFilter.entries,
            selected = MoodFilter.ALL,
            onSelect = onMoodClicked,
            label = { it.label },
            modifier = Modifier.padding(horizontal = dimens.horizontalPadding),
        )
    }
}

/** "Weiter hören" 2x2 grid of compact list-style tiles - up to 4 of the same
 * recently-played tracks the "Zuletzt gehört" shelf shows further down (see
 * [HomeViewModel]'s `recentlyPlayed` state), just as small tappable rows rather
 * than a shelf. Two plain [Row]s instead of a nested lazy grid: exactly 4 items
 * max, and a second scrollable inside Home's outer LazyColumn isn't worth it. */
@Composable
private fun ContinueGrid(tracks: List<TrackEntity>, onClick: (TrackEntity) -> Unit) {
    val dimens = rememberResponsiveDimens()
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text(
            "Weiter hören",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = dimens.horizontalPadding).padding(bottom = 10.dp),
        )
        tracks.chunked(2).forEach { rowTracks ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dimens.horizontalPadding)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowTracks.forEach { track ->
                    ContinueGridTile(track = track, onClick = { onClick(track) }, modifier = Modifier.weight(1f))
                }
                if (rowTracks.size < 2) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ContinueGridTile(track: TrackEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = rememberResponsiveDimens()
    Row(
        modifier = modifier
            .canopyCard(padding = 8.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackThumbnail(track.thumbnailUrl, size = dimens.continueTileThumbnail)
        Spacer(modifier = Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                track.artist ?: track.source,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = Canopy.neutral500,
            )
        }
    }
}

/** "Deine Mixes" mood card - a 168x96 thumbnail with a small accent "tag" badge
 * naming the mood (top-left), title/subtitle below. Tapping shuffle-plays the
 * mix's whole pool via [HomeViewModel.onMixCardClicked]. */
@Composable
private fun MixMoodCard(mix: MixCard, onClick: () -> Unit) {
    Column(modifier = Modifier.width(168.dp).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .width(168.dp)
                .height(96.dp)
                .clip(RoundedCornerShape(14.dp)),
        ) {
            TrackThumbnail(mix.thumbnailUrl, size = 168.dp, modifier = Modifier.fillMaxSize())
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Canopy.bg.copy(alpha = 0.55f)),
                        ),
                    ),
            )
            CanopyBadge(
                text = mix.badge,
                tone = CanopyBadgeTone.Accent,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            mix.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            mix.subtitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = Canopy.neutral500,
        )
    }
}

/** "Empfehlung des Tages" - one spotlight pick, no fabricated stats: the
 * subtitle is just the track's own artist and source, same info every other
 * track row on Home already shows. */
@Composable
private fun DailyPickCard(track: TrackResultDto, onClick: () -> Unit) {
    val dimens = rememberResponsiveDimens()
    Column(modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)) {
        Text(
            "EMPFEHLUNG DES TAGES",
            style = MaterialTheme.typography.labelSmall,
            color = Canopy.accent300,
            modifier = Modifier.padding(horizontal = dimens.horizontalPadding),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dimens.horizontalPadding)
                .padding(top = 10.dp)
                .canopyCard(padding = 10.dp)
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackThumbnail(track.thumbnailUrl, size = dimens.dailyPickThumbnail)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    track.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                )
                val sourceLabel = if (track.source == "soundcloud") "SoundCloud" else "YT Music"
                Text(
                    track.artist?.let { "$it · $sourceLabel" } ?: sourceLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = Canopy.neutral500,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            CanopyIconButton(
                icon = phosphorIcon("play", filled = true),
                onClick = onClick,
                variant = CanopyButtonVariant.Primary,
                shape = CircleShape,
                size = 34.dp,
                iconSize = 15.dp,
            )
        }
    }
}

/** "Sender entdecken" - artist-seeded station tile. No real radio/similar-
 * artist signal exists on-device, so tapping just shuffle-plays that artist's
 * own tracks (see [HomeViewModel.onStationClicked]); reuses the same
 * [TopArtist] pool "Neu von Künstlern" already resolved. */
@Composable
private fun StationItem(artist: TopArtist, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(76.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(66.dp)
                .clip(CircleShape)
                .background(Canopy.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                phosphorIcon("waveform"),
                contentDescription = null,
                tint = Canopy.neutral300,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text("Sender", style = MaterialTheme.typography.labelSmall, color = Canopy.neutral500)
        Text(
            artist.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
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
                        colors = listOf(Color.Transparent, Canopy.bg.copy(alpha = 0.75f)),
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
                color = Canopy.neutral300,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ShelfHeader(title: String, onSeeAll: (() -> Unit)?) {
    val dimens = rememberResponsiveDimens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.horizontalPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        if (onSeeAll != null) {
            Text(
                "Alle anzeigen",
                color = Canopy.accent,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable(onClick = onSeeAll),
            )
        }
    }
}

@Composable
private fun EmptyShelfHint(text: String) {
    val dimens = rememberResponsiveDimens()
    Text(
        text,
        color = Canopy.neutral500,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = dimens.horizontalPadding, vertical = 8.dp),
    )
}

/** ".ph.dots-three" overflow button overlaid on a shelf thumbnail's top-right
 * corner (24x24, translucent circular background) - the design handoff's pattern
 * for Charts/Für-dich/Zuletzt-gehört/Deine-Likes, absent from "Im Fokus" by design.
 * Wired to the same track-row action set every other screen already offers (Search,
 * Library, Playlist detail): add to playlist, add to queue, download, "Zum
 * Künstler", share - plus an optional unlike entry for the Likes shelf. */
@Composable
private fun TrackOverflowMenu(
    track: TrackResultDto,
    onAddToPlaylistClick: () -> Unit,
    onAddToQueueClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onArtistClick: () -> Unit,
    onUnlikeClick: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(Canopy.bg.copy(alpha = 0.55f))
            .clickable { expanded = true },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            phosphorIcon("dots-three"),
            contentDescription = "Mehr",
            tint = Canopy.text,
            modifier = Modifier.size(14.dp),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Zu Playlist hinzufügen") },
                leadingIcon = { Icon(phosphorIcon("plus-circle"), contentDescription = null, tint = Canopy.accent) },
                onClick = { expanded = false; onAddToPlaylistClick() },
            )
            DropdownMenuItem(
                text = { Text("Zur Warteschlange hinzufügen") },
                leadingIcon = { Icon(phosphorIcon("list-plus"), contentDescription = null, tint = Canopy.accent) },
                onClick = { expanded = false; onAddToQueueClick() },
            )
            DropdownMenuItem(
                text = { Text("Herunterladen") },
                leadingIcon = { Icon(phosphorIcon("download-simple"), contentDescription = null, tint = Canopy.accent) },
                onClick = { expanded = false; onDownloadClick() },
            )
            if (onUnlikeClick != null) {
                DropdownMenuItem(
                    text = { Text("Nicht mehr gefällt mir") },
                    leadingIcon = { Icon(phosphorIcon("heart", filled = true), contentDescription = null, tint = Canopy.accent) },
                    onClick = { expanded = false; onUnlikeClick() },
                )
            }
            DropdownMenuItem(
                text = { Text("Zum Künstler") },
                leadingIcon = { Icon(phosphorIcon("user-circle"), contentDescription = null, tint = Canopy.accent) },
                onClick = { expanded = false; onArtistClick() },
            )
            DropdownMenuItem(
                text = { Text("Teilen") },
                leadingIcon = { Icon(phosphorIcon("share-network"), contentDescription = null, tint = Canopy.accent) },
                onClick = { expanded = false; context.shareText(track.webpageUrl) },
            )
        }
    }
}

@Composable
private fun TrackShelfItem(
    title: String,
    subtitle: String?,
    thumbnailUrl: String?,
    onClick: () -> Unit,
    overflowMenu: (@Composable () -> Unit)? = null,
) {
    val dimens = rememberResponsiveDimens()
    Column(
        modifier = Modifier
            .width(dimens.shelfThumbnail)
            .clickable(onClick = onClick),
    ) {
        Box {
            TrackThumbnail(thumbnailUrl, size = dimens.shelfThumbnail)
            if (overflowMenu != null) {
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(5.dp)) {
                    overflowMenu()
                }
            }
        }
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
                color = Canopy.neutral500,
            )
        }
    }
}

@Composable
private fun PlaylistShelfItem(
    playlist: PlaylistOutDto,
    onClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onAddToQueueClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(148.dp)
            .canopyCard()
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
            color = Canopy.accent,
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
            color = Canopy.neutral500,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            CanopyIconButton(
                icon = phosphorIcon("download-simple"),
                onClick = onDownloadClick,
                size = 28.dp,
                iconSize = 14.dp,
            )
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                CanopyIconButton(
                    icon = phosphorIcon("dots-three"),
                    onClick = { menuExpanded = true },
                    size = 28.dp,
                    iconSize = 14.dp,
                )
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Zur Warteschlange hinzufügen") },
                        leadingIcon = { Icon(phosphorIcon("list-plus"), contentDescription = null, tint = Canopy.accent) },
                        onClick = { menuExpanded = false; onAddToQueueClick() },
                    )
                    DropdownMenuItem(
                        text = { Text("Löschen") },
                        leadingIcon = { Icon(phosphorIcon("trash"), contentDescription = null, tint = Canopy.accent) },
                        onClick = { menuExpanded = false; onDeleteClick() },
                    )
                }
            }
        }
    }
}

/** "Neu von Künstlern" avatar rail entry - a real followed artist ([TopArtist.
 * sourceId] non-null) shows its actual avatar; the frequency-based fallback (no id/
 * thumbnail yet, resolved on tap) falls back to the initial-letter placeholder. */
@Composable
private fun ArtistAvatarItem(artist: TopArtist, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(66.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .border(2.dp, Canopy.accent, CircleShape)
                .padding(2.dp)
                .clip(CircleShape)
                .background(Canopy.accent800),
            contentAlignment = Alignment.Center,
        ) {
            if (artist.thumbnailUrl != null) {
                AsyncImage(
                    model = artist.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
            } else {
                Text(
                    artist.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    color = Canopy.accent300,
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            artist.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
