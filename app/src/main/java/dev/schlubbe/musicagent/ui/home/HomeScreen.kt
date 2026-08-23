package dev.schlubbe.musicagent.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.schlubbe.musicagent.data.local.entity.TrackEntity
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.ui.components.CanopyAvatar
import dev.schlubbe.musicagent.ui.components.CanopyBadge
import dev.schlubbe.musicagent.ui.components.CanopyBadgeTone
import dev.schlubbe.musicagent.ui.components.CanopyButton
import dev.schlubbe.musicagent.ui.components.CanopyButtonVariant
import dev.schlubbe.musicagent.ui.components.CanopyChip
import dev.schlubbe.musicagent.ui.components.GeneratedArtwork
import dev.schlubbe.musicagent.ui.components.CanopyIconButton
import dev.schlubbe.musicagent.ui.components.CanopySectionHeader
import dev.schlubbe.musicagent.ui.components.EqualizerBadge
import dev.schlubbe.musicagent.ui.components.EqualizerBadgeVariant
import dev.schlubbe.musicagent.ui.components.TrackThumbnail
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.theme.CanopyPillShape
import dev.schlubbe.musicagent.ui.theme.CanopyShapes

// Canopy Home, rebuilt from GrooveoApp.dc.html's `isHome` block. Section order,
// paddings and type roles follow that markup, and every shelf is bound to data
// the app actually has -- see DailyPickCard for the one spot where the prototype
// showed a statistic with no real source.
private const val CONTENT_BOTTOM_PADDING = 150 // clears the mini player + tab bar
private const val SHELF_CARD_SIZE = 146
private const val SECTION_GAP = 26
private const val RESUME_GRID_COUNT = 4

@Composable
fun HomeScreen(
    onTrackSelected: () -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    // "Alle" on the followed-artists rail: the design points this at the artist
    // area, which in this app is Library's Künstler section.
    onSeeAllArtistsClick: () -> Unit,
    onArtistClick: (source: String, sourceId: String) -> Unit = { _, _ -> },
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    // Reloads on every (re)entry -- including returning from the Player -- so
    // shelves reflect likes/plays/playlist edits made elsewhere.
    LaunchedEffect(Unit) { viewModel.refresh() }

    LaunchedEffect(uiState.artistNavTarget) {
        uiState.artistNavTarget?.let { (source, sourceId) ->
            onArtistClick(source, sourceId)
            viewModel.onArtistNavigated()
        }
    }

    Scaffold(containerColor = Canopy.bg) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = CONTENT_BOTTOM_PADDING.dp),
        ) {
            item { HomeAppBar(uiState, onSearchClick, onDownloadsClick, onSettingsClick) }

            if (uiState.recentlyPlayed.isNotEmpty()) {
                item {
                    ResumeGrid(
                        tracks = uiState.recentlyPlayed.take(RESUME_GRID_COUNT),
                        onClick = {
                            viewModel.onRecentlyPlayedClicked(it)
                            onTrackSelected()
                        },
                    )
                }
            }

            if (uiState.charts.isNotEmpty() || uiState.isChartsLoading) {
                item {
                    MoreForYouShelf(
                        tracks = uiState.charts,
                        isLoading = uiState.isChartsLoading,
                        onShowAll = onSearchClick,
                        onClick = {
                            viewModel.onChartTrackClicked(it)
                            onTrackSelected()
                        },
                    )
                }
            }

            if (uiState.mixCards.isNotEmpty()) {
                item {
                    MixShelf(
                        mixes = uiState.mixCards,
                        onClick = {
                            viewModel.onMixCardClicked(it)
                            onTrackSelected()
                        },
                    )
                }
            }

            item {
                GenreTrends(
                    selected = uiState.selectedGenre,
                    tracks = uiState.genreTracks,
                    isLoading = uiState.isGenreLoading,
                    nowPlayingId = uiState.nowPlayingId,
                    onGenreSelected = viewModel::onGenreSelected,
                    onTrackClick = {
                        viewModel.onChartTrackClicked(it)
                        onTrackSelected()
                    },
                )
            }

            uiState.dailyPick?.let { pick ->
                item {
                    DailyPickCard(
                        pick = pick,
                        onPlay = {
                            viewModel.onDailyPickClicked()
                            onTrackSelected()
                        },
                    )
                }
            }

            if (!uiState.scPromoDismissed) {
                item { ScPromoCard(onDismiss = viewModel::dismissScPromo, onCta = onDownloadsClick) }
            }

            if (uiState.topArtists.isNotEmpty()) {
                item {
                    StationsShelf(
                        artists = uiState.topArtists,
                        onClick = {
                            viewModel.onStationClicked(it)
                            onTrackSelected()
                        },
                    )
                }
                item {
                    FollowedArtistsRail(
                        artists = uiState.topArtists,
                        onSeeAll = onSeeAllArtistsClick,
                        onClick = viewModel::onArtistAvatarClicked,
                    )
                }
            }
        }
    }
}

/** App bar: headline-md title, then search / downloads (with the coral badge
 * dot) / settings. The "Offline" badge shows only while data saver is on. */
@Composable
private fun HomeAppBar(
    uiState: HomeUiState,
    onSearch: () -> Unit,
    onDownloads: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Start", style = MaterialTheme.typography.headlineMedium, color = Canopy.text)
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (uiState.dataSaverMode) {
                CanopyBadge("Offline", tone = CanopyBadgeTone.Accent2)
            }
            CanopyIconButton(
                icon = phosphorIcon("magnifying-glass"),
                onClick = onSearch,
                contentDescription = "Suche",
            )
            CanopyIconButton(
                icon = phosphorIcon("download-simple"),
                onClick = onDownloads,
                badge = true,
                contentDescription = "Downloads",
            )
            CanopyIconButton(
                icon = phosphorIcon("sliders-horizontal"),
                onClick = onSettings,
                contentDescription = "Einstellungen",
            )
        }
    }
}

/** "Weiter hören": a 2x2 grid of compact tiles. Built as Rows rather than a
 * nested grid, which can't measure inside a LazyColumn item. */
@Composable
private fun ResumeGrid(tracks: List<TrackEntity>, onClick: (TrackEntity) -> Unit) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
        Text(
            "Weiter hören",
            style = MaterialTheme.typography.headlineSmall,
            color = Canopy.text,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        tracks.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { track ->
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(CanopyShapes.small)
                            .background(Canopy.surface)
                            .border(1.dp, Canopy.divider, CanopyShapes.small)
                            .clickable { onClick(track) }
                            .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TrackThumbnail(url = track.thumbnailUrl, size = 38.dp, seed = track.title)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                track.title,
                                style = MaterialTheme.typography.labelMedium,
                                color = Canopy.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                track.artist ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = Canopy.neutral500,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
                // Keeps a lone trailing tile at half width instead of stretching it.
                if (row.size == 1) Box(modifier = Modifier.weight(1f))
            }
        }
    }
}

/** "Mehr für dich": headline + "Alle anzeigen" chip, the on-device provenance
 * subline, then a horizontal shelf of 146dp covers. */
@Composable
private fun MoreForYouShelf(
    tracks: List<TrackResultDto>,
    isLoading: Boolean,
    onShowAll: () -> Unit,
    onClick: (TrackResultDto) -> Unit,
) {
    Column(modifier = Modifier.padding(top = SECTION_GAP.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Mehr für dich", style = MaterialTheme.typography.headlineSmall, color = Canopy.text)
            CanopyChip(label = "Alle anzeigen", active = false, onClick = onShowAll)
        }
        Text(
            "Auf dem Gerät aus Verlauf und Likes berechnet",
            style = MaterialTheme.typography.bodySmall,
            color = Canopy.neutral500,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
        if (isLoading && tracks.isEmpty()) {
            ShelfLoading()
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(tracks, key = { it.source + it.sourceId }) { track ->
                    Column(
                        modifier = Modifier
                            .width(SHELF_CARD_SIZE.dp)
                            .clickable { onClick(track) },
                    ) {
                        TrackThumbnail(
                            url = track.thumbnailUrl,
                            size = SHELF_CARD_SIZE.dp,
                            seed = track.title,
                        )
                        Text(
                            track.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = Canopy.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(
                            listOfNotNull(track.artist, sourceLabel(track.source)).joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = Canopy.neutral400,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** "Für dich abgemischt": Canopy TrackCards -- a cover with a coral badge
 * pinned top-left, then title and subtitle. */
@Composable
private fun MixShelf(mixes: List<MixCard>, onClick: (MixCard) -> Unit) {
    Column(modifier = Modifier.padding(top = SECTION_GAP.dp)) {
        Text(
            "Für dich abgemischt",
            style = MaterialTheme.typography.headlineSmall,
            color = Canopy.text,
            modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(mixes, key = { it.badge + it.title }) { mix ->
                Column(
                    modifier = Modifier
                        .width(SHELF_CARD_SIZE.dp)
                        .clickable { onClick(mix) },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box {
                        TrackThumbnail(
                            url = mix.thumbnailUrl,
                            size = SHELF_CARD_SIZE.dp,
                            seed = mix.title,
                        )
                        Text(
                            mix.badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier
                                .padding(8.dp)
                                .clip(CanopyPillShape)
                                .background(Canopy.accent2)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    Column {
                        Text(
                            mix.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = Canopy.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            mix.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = Canopy.neutral400,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** "Trends nach Genre": chips over a short track list. Each chip is its own
 * real SoundCloud genre chart, so selecting one refetches rather than filters. */
@Composable
private fun GenreTrends(
    selected: GenreFilter,
    tracks: List<TrackResultDto>,
    isLoading: Boolean,
    nowPlayingId: String?,
    onGenreSelected: (GenreFilter) -> Unit,
    onTrackClick: (TrackResultDto) -> Unit,
) {
    Column(modifier = Modifier.padding(top = SECTION_GAP.dp)) {
        Text(
            "Trends nach Genre",
            style = MaterialTheme.typography.headlineSmall,
            color = Canopy.text,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 14.dp),
        ) {
            items(GenreFilter.entries.toList(), key = { it.name }) { genre ->
                CanopyChip(
                    label = genre.label,
                    active = genre == selected,
                    onClick = { onGenreSelected(genre) },
                )
            }
        }
        when {
            isLoading && tracks.isEmpty() -> ShelfLoading()
            tracks.isEmpty() -> Text(
                "Für dieses Genre gerade keine Trends verfügbar.",
                style = MaterialTheme.typography.bodySmall,
                color = Canopy.neutral500,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            else -> Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                tracks.forEach { track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CanopyShapes.small)
                            .clickable { onTrackClick(track) }
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TrackThumbnail(url = track.thumbnailUrl, size = 46.dp, seed = track.title)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                track.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = Canopy.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                trackMeta(track),
                                style = MaterialTheme.typography.bodySmall,
                                color = Canopy.neutral500,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                        if ("${track.source}:${track.sourceId}" == nowPlayingId) {
                            EqualizerBadge(
                                isPlaying = true,
                                variant = EqualizerBadgeVariant.Inline,
                                size = 18.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "Die Auswahl von heute / Toll für dich": the gradient hero card.
 *
 * The prototype's bottom row read "1.175 Hörer haben den Titel heute geliked",
 * but that number is a hardcoded literal in the mockup -- no global like count
 * exists anywhere in the extraction (TrackResultDto has no such field). Rather
 * than invent one, this keeps the row's shape and fills it with metadata that
 * is genuinely available. */
@Composable
private fun DailyPickCard(pick: TrackResultDto, onPlay: () -> Unit) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = SECTION_GAP.dp)) {
        Text(
            "Die Auswahl von heute".uppercase(),
            style = MaterialTheme.typography.titleSmall,
            color = Canopy.neutral400,
        )
        Text(
            "Toll für dich",
            style = MaterialTheme.typography.headlineSmall,
            color = Canopy.text,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CanopyShapes.large)
                .background(
                    Brush.linearGradient(
                        colorStops = arrayOf(
                            0f to Canopy.accent200,
                            0.7f to Canopy.surface,
                            1f to Canopy.surface,
                        ),
                    ),
                )
                .border(1.dp, Canopy.divider, CanopyShapes.large)
                .clickable(onClick = onPlay)
                .padding(14.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackThumbnail(url = pick.thumbnailUrl, size = 62.dp, seed = pick.title)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        pick.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = Canopy.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        pick.artist ?: sourceLabel(pick.source),
                        style = MaterialTheme.typography.bodySmall,
                        color = Canopy.neutral600,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                CanopyIconButton(
                    icon = phosphorIcon("play", filled = true),
                    onClick = onPlay,
                    variant = CanopyButtonVariant.Primary,
                    size = 46.dp,
                    contentDescription = "Abspielen",
                )
            }
            HorizontalDivider(color = Canopy.divider, modifier = Modifier.padding(top = 14.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    phosphorIcon("waveform", filled = true),
                    contentDescription = null,
                    tint = Canopy.accent2,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    trackMeta(pick),
                    style = MaterialTheme.typography.bodySmall,
                    color = Canopy.neutral600,
                )
            }
        }
    }
}

/** Canopy PromoCard: centred, dismissible, outlined CTA. Carries the app's
 * honest SoundCloud-HLS download limitation. */
@Composable
private fun ScPromoCard(onDismiss: () -> Unit, onCta: () -> Unit) {
    Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = SECTION_GAP.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CanopyShapes.large)
                .background(Canopy.surface)
                .border(1.dp, Canopy.divider, CanopyShapes.large)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "SoundCloud-Downloads",
                style = MaterialTheme.typography.headlineSmall,
                color = Canopy.text,
                textAlign = TextAlign.Center,
            )
            Text(
                "SoundCloud liefert HLS-Streams. Downloads von dort sind noch nicht möglich.",
                style = MaterialTheme.typography.bodyMedium,
                color = Canopy.neutral500,
                textAlign = TextAlign.Center,
            )
            CanopyButton(
                text = "Mehr erfahren",
                onClick = onCta,
                variant = CanopyButtonVariant.Secondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        CanopyIconButton(
            icon = phosphorIcon("x"),
            onClick = onDismiss,
            size = 32.dp,
            contentDescription = "Ausblenden",
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
        )
    }
}

/** "Entdecken mit Sendern": a cover with the artist's ringed avatar pinned
 * bottom-left and a SENDER pill top-left. Stations are the artist-seeded pools
 * HomeViewModel.onStationClicked already builds. */
@Composable
private fun StationsShelf(artists: List<TopArtist>, onClick: (TopArtist) -> Unit) {
    Column(modifier = Modifier.padding(top = SECTION_GAP.dp)) {
        Text(
            "Entdecken mit Sendern",
            style = MaterialTheme.typography.headlineSmall,
            color = Canopy.text,
            modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(artists, key = { it.name + it.source }) { artist ->
                Column(
                    modifier = Modifier
                        .width(SHELF_CARD_SIZE.dp)
                        .clickable { onClick(artist) },
                ) {
                    Box {
                        TrackThumbnail(
                            url = artist.thumbnailUrl,
                            size = SHELF_CARD_SIZE.dp,
                            seed = artist.name,
                        )
                        Text(
                            "SENDER",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier
                                .padding(8.dp)
                                .clip(CanopyPillShape)
                                .background(Color.Black.copy(alpha = 0.55f))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                        CanopyAvatar(
                            initials = artist.name,
                            size = 44.dp,
                            ring = true,
                            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                        ) { GeneratedArtwork(seed = artist.name, modifier = Modifier.fillMaxSize()) }
                    }
                    Text(
                        "Sender: ${artist.name}",
                        style = MaterialTheme.typography.labelLarge,
                        color = Canopy.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        "Basierend auf ${artist.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Canopy.neutral400,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** "Neu von Künstlern, denen du folgst": a rail of 68dp avatars. */
@Composable
private fun FollowedArtistsRail(
    artists: List<TopArtist>,
    onSeeAll: () -> Unit,
    onClick: (TopArtist) -> Unit,
) {
    Column(modifier = Modifier.padding(top = SECTION_GAP.dp)) {
        CanopySectionHeader(
            title = "Neu von Künstlern, denen du folgst",
            action = "Alle",
            onActionClick = onSeeAll,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(artists, key = { it.name + it.source }) { artist ->
                Column(
                    modifier = Modifier
                        .width(84.dp)
                        .clickable { onClick(artist) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CanopyAvatar(initials = artist.name, size = 68.dp, ring = true) {
                        GeneratedArtwork(seed = artist.name, modifier = Modifier.fillMaxSize())
                    }
                    Text(
                        artist.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = Canopy.text,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShelfLoading() {
    Box(
        modifier = Modifier.fillMaxWidth().height(72.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = Canopy.accent, modifier = Modifier.size(24.dp))
    }
}

private fun sourceLabel(source: String) = if (source == "soundcloud") "SoundCloud" else "YT Music"

/** The "artist · source · length" meta line used on genre rows and the hero
 * card -- only fields the DTO actually carries, so nothing here is invented. */
private fun trackMeta(track: TrackResultDto): String = listOfNotNull(
    track.artist,
    sourceLabel(track.source),
    track.durationSec?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" },
).joinToString(" · ")
