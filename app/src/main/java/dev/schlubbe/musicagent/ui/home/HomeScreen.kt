package dev.schlubbe.musicagent.ui.home

import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import dev.schlubbe.musicagent.ui.theme.LocalChromeInset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.CircleShape
import dev.schlubbe.musicagent.ui.components.skeleton
import dev.schlubbe.musicagent.BuildConfig
import dev.schlubbe.musicagent.ui.theme.dawnContainer
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.schlubbe.musicagent.data.local.entity.TrackEntity
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.data.repository.FeedItem
import dev.schlubbe.musicagent.ui.components.CanopyAvatar
import dev.schlubbe.musicagent.ui.components.CanopyBadge
import dev.schlubbe.musicagent.ui.components.CanopyBadgeTone
import dev.schlubbe.musicagent.ui.components.CanopyButtonVariant
import dev.schlubbe.musicagent.ui.components.CanopyChip
import dev.schlubbe.musicagent.ui.components.CanopyIconButton
import dev.schlubbe.musicagent.ui.components.CanopySectionHeader
import dev.schlubbe.musicagent.ui.components.EqualizerBadge
import dev.schlubbe.musicagent.ui.components.EqualizerBadgeVariant
import dev.schlubbe.musicagent.ui.components.GeneratedArtwork
import dev.schlubbe.musicagent.ui.components.PullRefresh
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
    // "Alle" on the followed-artists rail: the design points this at the artist
    // area, which in this app is Library's Künstler section.
    onSeeAllArtistsClick: () -> Unit,
    onArtistClick: (source: String, sourceId: String) -> Unit = { _, _ -> },
    onWhatsNewClick: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(uiState.artistLookupError) {
        uiState.artistLookupError?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.onArtistLookupErrorShown()
        }
    }

    // First entry loads everything; later ones only the local shelves (see
    // HomeViewModel.onScreenEntered), so the feed stays put until the next app start.
    LaunchedEffect(Unit) { viewModel.onScreenEntered() }

    LaunchedEffect(uiState.artistNavTarget) {
        uiState.artistNavTarget?.let { (source, sourceId) ->
            onArtistClick(source, sourceId)
            viewModel.onArtistNavigated()
        }
    }

    Scaffold(containerColor = dawnContainer, contentWindowInsets = WindowInsets(0)) { padding ->
        PullRefresh(onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = maxOf(CONTENT_BOTTOM_PADDING.dp, LocalChromeInset.current + 16.dp)),
            ) {
                item { HomeAppBar(uiState, onSettingsClick) }

                if (uiState.showWhatsNewBanner) {
                    item {
                        WhatsNewBanner(
                            version = uiState.latestVersionLabel,
                            onOpen = {
                                viewModel.onWhatsNewBannerSeen()
                                onWhatsNewClick()
                            },
                            onDismiss = viewModel::onWhatsNewBannerSeen,
                        )
                    }
                }

                if (uiState.recentlyPlayed.isNotEmpty()) {
                    item {
                        ResumeGrid(
                            tracks = uiState.recentlyPlayed.take(if (BuildConfig.DAWN) 8 else RESUME_GRID_COUNT),
                            onClick = {
                                viewModel.onRecentlyPlayedClicked(it)
                                onTrackSelected()
                            },
                        )
                    }
                }

                if (uiState.onRepeat.isNotEmpty()) {
                    item {
                        TrackShelf("Dauerschleife", "Was du gerade immer wieder hörst", uiState.onRepeat) {
                            viewModel.onShelfTrackClicked(uiState.onRepeat, it)
                            onTrackSelected()
                        }
                    }
                }

                if (uiState.feed.isNotEmpty() || uiState.isFeedLoading) {
                    item {
                        MoreForYouShelf(
                            items = uiState.feed,
                            subtitle = uiState.moreForYouSubtitle,
                            isLoading = uiState.isFeedLoading,
                            onShowAll = onSearchClick,
                            onClick = {
                                viewModel.onFeedTrackClicked(it)
                                onTrackSelected()
                            },
                        )
                    }
                }

                if (uiState.showFeatured && uiState.featuredItems.isNotEmpty()) {
                    item {
                        TrackShelf("Im Fokus", "Was gerade wirklich groß ist", uiState.featuredItems) {
                            viewModel.onChartTrackClicked(it)
                            onTrackSelected()
                        }
                    }
                }

                if (uiState.showMixControls && uiState.mixCards.isNotEmpty()) {
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
                        genres = uiState.genreFilters,
                        selected = uiState.selectedGenre,
                        tracks = uiState.genreTracks,
                        isLoading = uiState.isGenreLoading,
                        nowPlayingId = uiState.nowPlayingId,
                        onGenreSelected = viewModel::onGenreSelected,
                        onTrackClick = {
                            viewModel.onGenreTrackClicked(it)
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

                if (uiState.rediscover.isNotEmpty()) {
                    item {
                        TrackShelf("Wiederentdecken", "Lange nicht gehört, früher oft", uiState.rediscover) {
                            viewModel.onShelfTrackClicked(uiState.rediscover, it)
                            onTrackSelected()
                        }
                    }
                }

                if (uiState.showNewUploads && uiState.topArtists.isNotEmpty()) {
                    item {
                        StationsShelf(
                            artists = uiState.topArtists,
                            loading = uiState.isStationLoading,
                            onClick = { viewModel.onStationClicked(it, onStarted = onTrackSelected) },
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
}

/** Dawn Music: Spotify's quick-access tiles under the greeting - cover flush
 * on the left, bold title, a frosted white tile; no heading, two columns. */
@Composable
private fun QuickAccessGrid(tracks: List<TrackEntity>, onClick: (TrackEntity) -> Unit) {
    Column(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tracks.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { track ->
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clip(CanopyShapes.small)
                            .background(Canopy.surface.copy(alpha = 0.72f))
                            .clickable { onClick(track) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TrackThumbnail(url = track.thumbnailUrl, size = 56.dp, seed = track.title, square = true)
                        Text(
                            track.title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Canopy.text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp),
                        )
                    }
                }
                if (row.size == 1) Box(modifier = Modifier.weight(1f))
            }
        }
    }
}

/** App bar: headline-md title, then search / downloads
 * / settings. The "Offline" badge shows only while data saver is on. */
@Composable
private fun HomeAppBar(
    uiState: HomeUiState,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (BuildConfig.DAWN) {
            // Spotify's header: profile bubble, then the greeting as the title
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Canopy.accent, Canopy.accent2)))
                        .clickable(onClick = onSettings),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        uiState.profileName.trim().take(1).uppercase().ifEmpty { "♪" },
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                // greeting, then the name on its own line so neither gets cut off
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        uiState.greetingBase,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Canopy.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (uiState.profileName.isNotBlank()) {
                        Text(
                            uiState.profileName.trim(),
                            style = MaterialTheme.typography.titleMedium,
                            color = Canopy.neutral600,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        } else {
            Text("Start", style = MaterialTheme.typography.headlineMedium, color = Canopy.text)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (uiState.dataSaverMode) {
                CanopyBadge("Offline", tone = CanopyBadgeTone.Accent2)
            }
            // Dawn Music on a holiday: its watercolour motif next to the greeting
            dev.schlubbe.musicagent.ui.theme.LocalHoliday.current?.look?.let { look ->
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(look.art),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                )
            }
            // Suche and Downloads are two taps away in the bottom bar already - the
            // header only carries what isn't reachable there.
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
    if (BuildConfig.DAWN) {
        QuickAccessGrid(tracks, onClick)
        return
    }
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

/** "Mehr für dich": headline + "Alle anzeigen" chip, a provenance subline that's
 * honest about whether history/likes or onboarding's taste picks are actually
 * behind it (see HomeViewModel.loadFeed), then a horizontal shelf of 146dp
 * covers. Backed by [FeedRepository.getFeed]'s own familiar+novel mix rather
 * than raw charts, so it's genuinely the shelf its label describes. */
@Composable
private fun MoreForYouShelf(
    items: List<FeedItem>,
    subtitle: String,
    isLoading: Boolean,
    onShowAll: () -> Unit,
    onClick: (FeedItem) -> Unit,
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
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = Canopy.neutral500,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
        if (isLoading && items.isEmpty()) {
            ShelfLoading()
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.track.source + it.track.sourceId }) { item ->
                    val track = item.track
                    Column(
                        modifier = Modifier
                            .width(SHELF_CARD_SIZE.dp)
                            .clickable { onClick(item) },
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
 * real SoundCloud genre chart, so selecting one refetches rather than filters.
 * [genres] is already ordered with the user's onboarding picks first (see
 * HomeViewModel.buildGenreFilters) - this composable just renders whatever
 * order it's handed. */
@Composable
private fun GenreTrends(
    genres: List<GenreFilter>,
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
            items(genres, key = { it.label }) { genre ->
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
                        pick.artist ?: sourceLabel(pick.source).orEmpty(),
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

/** A titled horizontal row of track cards, as "Mehr für dich" draws them. */
@Composable
private fun TrackShelf(title: String, subtitle: String, tracks: List<TrackResultDto>, onClick: (TrackResultDto) -> Unit) {
    Column(modifier = Modifier.padding(top = SECTION_GAP.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Canopy.text, modifier = Modifier.padding(horizontal = 16.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = Canopy.neutral500,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 12.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(tracks, key = { it.source + it.sourceId }) { track ->
                Column(modifier = Modifier.width(SHELF_CARD_SIZE.dp).clickable { onClick(track) }) {
                    TrackThumbnail(url = track.thumbnailUrl, size = SHELF_CARD_SIZE.dp, seed = track.title)
                    Text(
                        track.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = Canopy.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        track.artist.orEmpty(),
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

/** Shown once after an update: what the new version brings, one tap away. */
@Composable
private fun WhatsNewBanner(version: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            .fillMaxWidth()
            .clip(CanopyShapes.large)
            .background(Canopy.surface)
            .border(1.dp, Canopy.divider, CanopyShapes.large)
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Neu in Version $version", style = MaterialTheme.typography.titleSmall, color = Canopy.text)
            Text("Tippe, um zu sehen, was sich geändert hat", style = MaterialTheme.typography.bodySmall, color = Canopy.neutral500)
        }
        CanopyIconButton(icon = phosphorIcon("x"), onClick = onDismiss, size = 40.dp, contentDescription = "Ausblenden")
    }
}

/** "Entdecken mit Sendern": a cover with the artist's ringed avatar pinned
 * bottom-left and a SENDER pill top-left. Stations are the artist-seeded pools
 * HomeViewModel.onStationClicked already builds. */
@Composable
private fun StationsShelf(artists: List<TopArtist>, loading: Boolean, onClick: (TopArtist) -> Unit) {
    Column(modifier = Modifier.padding(top = SECTION_GAP.dp)) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Entdecken mit Sendern", style = MaterialTheme.typography.headlineSmall, color = Canopy.text)
            if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Canopy.accent)
        }
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
                        // the real profile photo of a followed artist, over the
                        // generated art that stays as placeholder and fallback
                        if (artist.thumbnailUrl != null) {
                            AsyncImage(
                                model = artist.thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
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
    // three cover-shaped placeholders, the size of a shelf's real tiles
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(3) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(150.dp).clip(CanopyShapes.medium).skeleton())
                Box(Modifier.size(width = 110.dp, height = 12.dp).clip(CanopyShapes.small).skeleton())
                Box(Modifier.size(width = 70.dp, height = 10.dp).clip(CanopyShapes.small).skeleton())
            }
        }
    }
}

/** Where a result comes from - not shown in Dawn Music, where a song is just a song. */
private fun sourceLabel(source: String): String? = if (BuildConfig.DAWN) null else if (source == "soundcloud") "SoundCloud" else "YT Music"

/** The "artist · source · length" meta line used on genre rows and the hero
 * card -- only fields the DTO actually carries, so nothing here is invented. */
private fun trackMeta(track: TrackResultDto): String = listOfNotNull(
    track.artist,
    sourceLabel(track.source),
    track.durationSec?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" },
).joinToString(" · ")
