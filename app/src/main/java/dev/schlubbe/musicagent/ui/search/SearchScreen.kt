package dev.schlubbe.musicagent.ui.search

import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import dev.schlubbe.musicagent.ui.theme.LocalChromeInset
import dev.schlubbe.musicagent.ui.theme.dawnContainer
import dev.schlubbe.musicagent.BuildConfig
import dev.schlubbe.musicagent.ui.components.CanopyButton
import dev.schlubbe.musicagent.ui.components.CanopyButtonVariant
import dev.schlubbe.musicagent.ui.components.ANALYZE_LABEL
import dev.schlubbe.musicagent.ui.components.LocalTrackAnalyzer

import android.widget.Toast
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import dev.schlubbe.musicagent.ui.components.CanopyIconButton
import dev.schlubbe.musicagent.ui.components.PullRefresh
import dev.schlubbe.musicagent.ui.util.shareText
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.schlubbe.musicagent.data.local.entity.DownloadState
import dev.schlubbe.musicagent.data.remote.dto.ArtistResultDto
import dev.schlubbe.musicagent.data.remote.dto.AlbumResultDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistResultDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.ui.components.CanopyAvatar
import dev.schlubbe.musicagent.ui.components.CanopyChip
import dev.schlubbe.musicagent.ui.components.CanopySectionHeader
import dev.schlubbe.musicagent.ui.components.TrackThumbnail
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.playlist.AddToPlaylistDialog
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.theme.CanopyPillShape
import dev.schlubbe.musicagent.ui.theme.CanopyShapes
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex

// Canopy Search, from GrooveoApp.dc.html's `isSearch` block: a pill search
// field, source chips, underline tabs, then result rows each carrying a state
// pill (coral for anything blocking, neutral otherwise).
//
// The design shows three tabs; this keeps the app's existing four, since
// "Alben" is a working result type and the mockup omitting it isn't a reason to
// delete a feature. The tab strip scrolls, so the extra one costs nothing.
private val SOURCES = listOf(
    "all" to "Alle Quellen",
    "soundcloud" to "SoundCloud",
    "ytmusic" to "YouTube Music",
)

private val RESULT_TABS = listOf(
    "all" to "Alle",
    "tracks" to "Titel",
    "artists" to "Künstler",
    // ARD, LibriVox and YouTube in one place: people look for a story, not a source
    "spoken" to "Hörspiele & Hörbücher",
    "podcasts" to "Podcasts",
    "albums" to "Alben",
    "playlists" to "Playlists",
)

private const val CONTENT_BOTTOM_PADDING = 150

@Composable
fun SearchScreen(
    onTrackSelected: () -> Unit,
    onArtistSelected: (source: String, sourceId: String) -> Unit,
    onPlaylistSelected: (source: String, sourceId: String) -> Unit,
    onPodcastSelected: (showId: String) -> Unit = {},
    onOpenPodcasts: () -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val analyzer = LocalTrackAnalyzer.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    LaunchedEffect(uiState.openPlayer) {
        if (uiState.openPlayer) {
            focusManager.clearFocus()
            viewModel.onPlayerOpened()
            onTrackSelected()
        }
    }
    LaunchedEffect(uiState.artistNavTarget) {
        uiState.artistNavTarget?.let { (source, sourceId) ->
            onArtistSelected(source, sourceId)
            viewModel.onArtistNavigated()
        }
    }
    LaunchedEffect(uiState.remotePlaylistNavTarget) {
        uiState.remotePlaylistNavTarget?.let { (source, sourceId) ->
            onPlaylistSelected(source, sourceId)
            viewModel.onRemotePlaylistNavigated()
        }
    }
    LaunchedEffect(uiState.artistLookupError) {
        uiState.artistLookupError?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.onArtistLookupErrorShown()
        }
    }

    if (uiState.trackPendingPlaylistAdd != null) {
        AddToPlaylistDialog(
            playlists = uiState.playlists,
            onDismiss = viewModel::dismissAddToPlaylist,
            onPlaylistPicked = viewModel::onPlaylistPicked,
            onCreatePlaylist = viewModel::onCreatePlaylistAndAdd,
        )
    }

    Scaffold(containerColor = dawnContainer, contentWindowInsets = WindowInsets(0)) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SearchHeader(
                query = uiState.query,
                source = uiState.source,
                resultType = uiState.resultType,
                onQueryChanged = viewModel::onQueryChanged,
                onSubmit = viewModel::runSearch,
                onSourceChanged = viewModel::onSourceChanged,
                onResultTypeChanged = viewModel::onResultTypeChanged,
            )

            PullRefresh(onRefresh = viewModel::runSearch, modifier = Modifier.weight(1f)) {
                when {
                    uiState.isLoading -> CenteredMessage { CircularProgressIndicator(color = Canopy.accent) }
                    uiState.error != null -> CenteredMessage {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                uiState.error!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Canopy.accent2,
                            )
                            CanopyButton(
                                text = "Erneut versuchen",
                                onClick = { viewModel.runSearch() },
                                variant = CanopyButtonVariant.Secondary,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                    uiState.query.isBlank() -> BrowseAll(
                        history = uiState.searchHistory,
                        covers = viewModel.browseCovers.collectAsState().value,
                        onShown = { viewModel.loadBrowseCovers(BROWSE_TILES.map { it.query }) },
                        onSearch = { query ->
                            focusManager.clearFocus()
                            viewModel.onHistoryQueryTapped(query)
                        },
                        onSpokenSearch = { query ->
                            focusManager.clearFocus()
                            viewModel.onSpokenTileTapped(query)
                        },
                        onOpenPodcasts = onOpenPodcasts,
                        onCoverFailed = viewModel::onBrowseCoverFailed,
                    )
                    uiState.suggestions.isNotEmpty() -> SuggestionsList(
                        suggestions = uiState.suggestions,
                        onTap = viewModel::onSuggestionTapped,
                    )
                    else -> ResultsList(
                        uiState = uiState,
                        onTrackClick = {
                            // Otherwise the keyboard stays up over the Player it opens.
                            focusManager.clearFocus()
                            viewModel.onTrackClicked(it)
                            onTrackSelected()
                        },
                        onArtistClick = viewModel::onArtistResultClicked,
                        onPlaylistClick = viewModel::onPlaylistResultClicked,
                        onShowClick = {
                            focusManager.clearFocus()
                            onPodcastSelected(it)
                        },
                        onShowAll = viewModel::onResultTypeChanged,
                        onHistoryTapped = viewModel::onHistoryQueryTapped,
                        onHistoryDeleted = viewModel::onHistoryQueryDeleted,
                        onEnableYouTube = viewModel::enableYouTube,
                        trackActions = TrackActions(
                            onAnalyze = { track -> analyzer.analyzeTracks(listOf(track)) },
                            onLikeToggle = viewModel::onLikeToggled,
                            onDownload = { track ->
                                viewModel.onDownloadClicked(track)
                                Toast.makeText(context, "„${track.title}“ wird heruntergeladen", Toast.LENGTH_SHORT).show()
                            },
                            onAddToQueue = { track ->
                                viewModel.onAddToQueueClicked(track)
                                Toast.makeText(context, "Als Nächstes eingereiht", Toast.LENGTH_SHORT).show()
                            },
                            onAddToPlaylist = viewModel::onAddToPlaylistClicked,
                            onArtist = viewModel::onTrackArtistClicked,
                            onShare = { track -> context.shareText(track.webpageUrl) },
                        ),
                    )
                }
            }
        }
    }
}

/** One "Alle durchsuchen" tile: what it says and what it searches for. */
private data class BrowseTile(val label: String, val query: String, val color: Color)

// dark enough everywhere for bold white labels (>= 3:1)
private val BROWSE_TILES = listOf(
    BrowseTile("Charts", "Top Hits 2026", Color(0xFF1665C8)),
    BrowseTile("Party", "Party Hits", Color(0xFFC2410C)),
    BrowseTile("Chill", "Chill Vibes", Color(0xFF2F7F73)),
    BrowseTile("Workout", "Workout Musik", Color(0xFFB83A3A)),
    BrowseTile("Fokus", "Focus Music", Color(0xFF3A5FCD)),
    BrowseTile("Sommer", "Sommerhits", Color(0xFFB5651D)),
    BrowseTile("Deutschrap", "Deutschrap", Color(0xFF7B4AA8)),
    BrowseTile("Pop", "Pop Hits", Color(0xFF8E3B6E)),
    BrowseTile("Hip-Hop", "Hip-Hop", Color(0xFF1F7A8C)),
    BrowseTile("House", "House Music", Color(0xFFA0522D)),
    BrowseTile("Techno", "Techno", Color(0xFF263447)),
    BrowseTile("R&B", "R&B", Color(0xFF9C2F5A)),
    BrowseTile("Rock", "Rock Classics", Color(0xFF5A4FB0)),
    BrowseTile("Indie", "Indie", Color(0xFF2E6E4E)),
    BrowseTile("Lo-Fi", "Lofi Hip Hop", Color(0xFF6B5B95)),
    BrowseTile("Latin", "Reggaeton", Color(0xFFC2401F)),
    BrowseTile("K-Pop", "K-Pop", Color(0xFFB0306A)),
    BrowseTile("Schlager", "Schlager", Color(0xFF8A5A00)),
)

// searched on the "Hörspiele & Hörbücher" tab
private val SPOKEN_TILES = listOf(
    BrowseTile("Krimi", "Krimi", Color(0xFF263447)),
    BrowseTile("Fantasy", "Fantasy", Color(0xFF5A4FB0)),
    BrowseTile("Science-Fiction", "Science Fiction", Color(0xFF1F5F8C)),
    BrowseTile("Kinder", "Kinder", Color(0xFFC2410C)),
    BrowseTile("Klassiker", "Klassiker", Color(0xFF8A5A00)),
    BrowseTile("Märchen", "Märchen", Color(0xFF2E6E4E)),
)

/** The empty search (both apps): recent searches as chips, then Spotify's
 * "Alle durchsuchen" grid of colour tiles that each start a search. */
@Composable
private fun BrowseAll(
    history: List<String>,
    covers: Map<String, String>,
    onShown: () -> Unit,
    onSearch: (String) -> Unit,
    onSpokenSearch: (String) -> Unit,
    onOpenPodcasts: () -> Unit,
    onCoverFailed: (String) -> Unit,
) {
    LaunchedEffect(Unit) { onShown() }
    val holiday = dev.schlubbe.musicagent.ui.theme.LocalHoliday.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = LocalChromeInset.current + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (history.isNotEmpty()) {
            item(span = { GridItemSpan(2) }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Zuletzt gesucht", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Canopy.text)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        history.take(8).forEach { q -> CanopyChip(label = q, active = false, onClick = { onSearch(q) }) }
                    }
                }
            }
        }
        // on a holiday: a banner for its music (Halloween party, Weihnachtslieder ...)
        holiday?.let { h ->
            val look = h.look
            if (look != null && h.searchLabel != null && h.searchQuery != null) {
                item(span = { GridItemSpan(2) }) {
                    Row(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .height(104.dp)
                            .clip(CanopyShapes.medium)
                            .background(Canopy.accent)
                            .clickable { onSearch(h.searchQuery) }
                            .padding(start = 18.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(h.searchLabel, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Passende Musik für heute", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        }
                        androidx.compose.foundation.Image(
                            androidx.compose.ui.res.painterResource(look.art),
                            contentDescription = null,
                            modifier = Modifier.size(92.dp),
                        )
                    }
                }
            }
        }
        // Hörspiele and Hörbücher get their own row, so nobody has to guess that the
        // music search finds them too
        item(span = { GridItemSpan(2) }) {
            Text(
                "Hörspiele & Hörbücher",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Canopy.text,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        item(span = { GridItemSpan(2) }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CanopyShapes.small)
                    .background(Canopy.surface)
                    .border(1.dp, Canopy.divider, CanopyShapes.small)
                    .clickable(onClick = onOpenPodcasts)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(phosphorIcon("headphones"), contentDescription = null, tint = Canopy.accent, modifier = Modifier.size(26.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Stöbern", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Canopy.text)
                    Text(
                        "Neue Hörspiele, Hörbücher und Podcasts, deine Abos und Weiterhören",
                        style = MaterialTheme.typography.bodySmall,
                        color = Canopy.neutral500,
                    )
                }
                Icon(phosphorIcon("caret-right"), contentDescription = null, tint = Canopy.neutral500, modifier = Modifier.size(18.dp))
            }
        }
        items(SPOKEN_TILES, key = { "spoken:" + it.label }) { tile ->
            Box(
                modifier = Modifier
                    .height(64.dp)
                    .clip(CanopyShapes.small)
                    .background(tile.color)
                    .clickable { onSpokenSearch(tile.query) }
                    .padding(12.dp),
            ) {
                Text(tile.label, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
        item(span = { GridItemSpan(2) }) {
            Text(
                "Alle durchsuchen",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Canopy.text,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(BROWSE_TILES, key = { it.label }) { tile ->
            Box(
                modifier = Modifier
                    .height(96.dp)
                    .clip(CanopyShapes.small)
                    .background(tile.color)
                    .clickable { onSearch(tile.query) }
                    .padding(12.dp),
            ) {
                Text(
                    tile.label,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).zIndex(1f),
                )
                // Spotify's tilted cover in the corner: a playlist cover for the
                // tile's search, a translucent card until it has loaded
                val cover = covers[tile.query]
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(64.dp)
                        .graphicsLayer { rotationZ = 22f; translationX = 20.dp.toPx(); translationY = 12.dp.toPx() }
                        .shadow(8.dp, CanopyShapes.small)
                        .clip(CanopyShapes.small)
                        .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.1f)))),
                ) {
                    if (cover != null) {
                        AsyncImage(
                            // category cards keep the full-size cover (ArtworkUrls.FULL)
                            model = coil.request.ImageRequest.Builder(LocalContext.current).data(cover)
                                .setParameter(dev.schlubbe.musicagent.data.artwork.ArtworkUrls.FULL, true).build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            onError = { onCoverFailed(tile.query) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHeader(
    query: String,
    source: String,
    resultType: String,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onSourceChanged: (String) -> Unit,
    onResultTypeChanged: (String) -> Unit,
) {
    Column(modifier = Modifier.background(dawnContainer).padding(top = 14.dp)) {
        if (BuildConfig.DAWN) {
            Text(
                "Suchen",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Canopy.text,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(CanopyPillShape)
                .background(Canopy.surface)
                .border(1.dp, Canopy.divider, CanopyPillShape)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                phosphorIcon("magnifying-glass"),
                contentDescription = null,
                tint = Canopy.neutral500,
                modifier = Modifier.size(18.dp),
            )
            Box(modifier = Modifier.weight(1f)) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChanged,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Canopy.text),
                    cursorBrush = SolidColor(Canopy.accent),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = { onSubmit() },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (query.isEmpty()) {
                    Text(
                        "Songs, Künstler, Hörspiele, Hörbücher",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Canopy.neutral500,
                    )
                }
            }
            if (query.isNotEmpty()) {
                Icon(
                    phosphorIcon("x"),
                    contentDescription = "Suche löschen",
                    tint = Canopy.neutral400,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onQueryChanged("") },
                )
            }
        }

        // Dawn Music: always both sources, no technical choice up front
        if (BuildConfig.DAWN) {
            Spacer(modifier = Modifier.height(12.dp))
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 12.dp),
            ) {
                items(SOURCES, key = { it.first }) { (key, label) ->
                    CanopyChip(
                        label = label,
                        active = source == key,
                        onClick = { onSourceChanged(key) },
                    )
                }
            }
        }
        // ...and the result tabs only once there is something to sort
        if (query.isBlank()) return@Column

        // Underline tabs: active gets full-strength text plus a 2dp accent rule,
        // inactive is neutral-500 with no rule. Scrolls to the active one, which
        // "Alle anzeigen" may pick off-screen.
        val tabsState = androidx.compose.foundation.lazy.rememberLazyListState()
        val tabs = RESULT_TABS
        LaunchedEffect(resultType) {
            val index = tabs.indexOfFirst { it.first == resultType }
            if (index >= 0) tabsState.animateScrollToItem(maxOf(0, index - 1))
        }
        LazyRow(
            state = tabsState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(tabs, key = { it.first }) { (key, label) ->
                val selected = resultType == key
                Column(
                    modifier = Modifier.clickable { onResultTypeChanged(key) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = if (selected) Canopy.text else Canopy.neutral500,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(if (selected) Canopy.accent else androidx.compose.ui.graphics.Color.Transparent),
                    )
                }
            }
        }
        HorizontalDivider(color = Canopy.divider)
    }
}

/** Search-as-you-type suggestions, shown in place of the (stale) previous results
 * while the query is being typed. */
@Composable
private fun SuggestionsList(suggestions: List<String>, onTap: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = maxOf(CONTENT_BOTTOM_PADDING.dp, LocalChromeInset.current + 16.dp)),
    ) {
        items(suggestions, key = { it }) { suggestion ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CanopyShapes.small)
                    .clickable { onTap(suggestion) }
                    .padding(horizontal = 6.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    phosphorIcon("magnifying-glass"),
                    contentDescription = null,
                    tint = Canopy.neutral500,
                    modifier = Modifier.size(18.dp),
                )
                Text(suggestion, style = MaterialTheme.typography.bodyMedium, color = Canopy.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ResultsList(
    uiState: SearchUiState,
    onTrackClick: (TrackResultDto) -> Unit,
    onArtistClick: (ArtistResultDto) -> Unit,
    onPlaylistClick: (source: String, sourceId: String) -> Unit,
    onShowClick: (String) -> Unit,
    onShowAll: (String) -> Unit,
    onHistoryTapped: (String) -> Unit,
    onHistoryDeleted: (String) -> Unit,
    onEnableYouTube: () -> Unit,
    trackActions: TrackActions,
) {
    // Sections that arrive later land above the ones already shown; until the list is
    // scrolled by hand it stays at the top instead of following the old first row.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var scrolledByHand by remember(uiState.query, uiState.resultType) { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is androidx.compose.foundation.interaction.DragInteraction.Start) scrolledByHand = true
        }
    }
    LaunchedEffect(uiState.spokenPending, uiState.podcastResults, uiState.hoerspielResults, uiState.hoerbuchResults, uiState.albumResults, uiState.playlistResults) {
        if (!scrolledByHand) listState.scrollToItem(0)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = maxOf(CONTENT_BOTTOM_PADDING.dp, LocalChromeInset.current + 16.dp)),
    ) {
        when (uiState.resultType) {
            "all" -> allSections(uiState, onTrackClick, onArtistClick, onPlaylistClick, onShowClick, onShowAll, trackActions)
            "artists" -> items(uiState.artistResults, key = { it.source + it.sourceId }) { ArtistResult(it, onArtistClick) }
            "playlists" -> items(uiState.playlistResults, key = { it.source + it.sourceId }) { PlaylistResult(it, onPlaylistClick) }
            "podcasts" -> items(uiState.podcastResults, key = { it.id }) { ShowResult(it, "Podcast", onShowClick) }
            "spoken" -> spokenSections(uiState, onTrackClick, onArtistClick, onShowClick, onEnableYouTube, trackActions)
            "albums" -> items(uiState.albumResults, key = { it.source + it.sourceId }) { AlbumResult(it, onPlaylistClick) }
            else -> items(uiState.results, key = { it.source + it.sourceId }) { track ->
                TrackResult(track, uiState, onTrackClick, trackActions)
            }
        }

        if (uiState.searchHistory.isNotEmpty()) {
            item {
                Column(modifier = Modifier.padding(top = 18.dp)) {
                    CanopySectionHeader(title = "Zuletzt gesucht")
                    // Wraps rather than scrolling, matching the design's flex-wrap.
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        uiState.searchHistory.forEach { q ->
                            HistorySearchChip(
                                label = q,
                                onClick = { onHistoryTapped(q) },
                                onDelete = { onHistoryDeleted(q) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "Alle", laid out like SoundCloud's: the best match first, then a few of each kind
 * with "Alle anzeigen" leading to that kind's tab, and the remaining tracks last. */
private fun androidx.compose.foundation.lazy.LazyListScope.allSections(
    uiState: SearchUiState,
    onTrackClick: (TrackResultDto) -> Unit,
    onArtistClick: (ArtistResultDto) -> Unit,
    onPlaylistClick: (source: String, sourceId: String) -> Unit,
    onShowClick: (String) -> Unit,
    onShowAll: (String) -> Unit,
    trackActions: TrackActions,
) {
    val query = uiState.query.trim()
    val topArtist = uiState.artistResults.firstOrNull()?.takeIf { matchesQuery(it.name, query) }
    val topTrack = uiState.results.firstOrNull()
    val artists = uiState.artistResults.filter { it !== topArtist }.take(ALL_SECTION_SIZE)
    val tracks = uiState.results.drop(1).take(ALL_TRACKS)
    val more = uiState.results.drop(1 + ALL_TRACKS)
    val nothing = topTrack == null && uiState.artistResults.isEmpty() && uiState.podcastResults.isEmpty() &&
        uiState.hoerspielResults.isEmpty() && uiState.hoerbuchResults.isEmpty() && uiState.albumResults.isEmpty() && uiState.playlistResults.isEmpty()
    if (nothing) {
        item { Text("Nichts gefunden", color = Canopy.neutral500, modifier = Modifier.padding(vertical = 24.dp)) }
        return
    }

    if (topArtist != null || topTrack != null) {
        item(key = "h:best") { AllHeader("Bestes Ergebnis") }
        topArtist?.let { item(key = "best:artist") { ArtistResult(it, onArtistClick) } }
        topTrack?.let { item(key = "best:track") { TrackResult(it, uiState, onTrackClick, trackActions) } }
    }
    fun <T> section(title: String, tab: String, list: List<T>, key: (T) -> String, target: String = tab, row: @Composable (T) -> Unit) {
        if (list.isEmpty()) return
        item(key = "h:$tab") { AllHeader(title) { onShowAll(target) } }
        items(list.take(ALL_SECTION_SIZE), key = { "$tab:${key(it)}" }) { row(it) }
    }
    section("Künstler", "artists", artists, { it.source + it.sourceId }) { ArtistResult(it, onArtistClick) }
    if (tracks.isNotEmpty()) {
        item(key = "h:tracks") { AllHeader("Titel") { onShowAll("tracks") } }
        items(tracks, key = { "tracks:${it.source}${it.sourceId}" }) { TrackResult(it, uiState, onTrackClick, trackActions) }
    }
    section("Hörspiele", "spoken", uiState.hoerspielResults, { it.id }) { ShowResult(it, "Hörspiel", onShowClick) }
    section("Hörbücher", "books", uiState.hoerbuchResults, { it.id }, target = "spoken") { ShowResult(it, "Hörbuch", onShowClick) }
    section("Podcasts", "podcasts", uiState.podcastResults, { it.id }) { ShowResult(it, "Podcast", onShowClick) }
    section("Alben", "albums", uiState.albumResults, { it.source + it.sourceId }) { AlbumResult(it, onPlaylistClick) }
    section("Playlists", "playlists", uiState.playlistResults, { it.source + it.sourceId }) { PlaylistResult(it, onPlaylistClick) }
    if (more.isNotEmpty()) {
        item(key = "h:more") { AllHeader("Weitere Ergebnisse") }
        items(more, key = { "more:${it.source}${it.sourceId}" }) { TrackResult(it, uiState, onTrackClick, trackActions) }
    }
}

/** "Hörspiele & Hörbücher": ARD and LibriVox first, then YouTube's series, albums and
 * single videos. Each section appears as its source answers. */
private fun androidx.compose.foundation.lazy.LazyListScope.spokenSections(
    uiState: SearchUiState,
    onTrackClick: (TrackResultDto) -> Unit,
    onArtistClick: (ArtistResultDto) -> Unit,
    onShowClick: (String) -> Unit,
    onEnableYouTube: () -> Unit,
    trackActions: TrackActions,
) {
    if (uiState.hoerspielResults.isNotEmpty()) {
        item(key = "h:hs") { AllHeader("Hörspiele") }
        items(uiState.hoerspielResults, key = { "hs:" + it.id }) { ShowResult(it, "Hörspiel", onShowClick) }
    }
    if (uiState.hoerbuchResults.isNotEmpty()) {
        item(key = "h:hb") { AllHeader("Hörbücher") }
        items(uiState.hoerbuchResults, key = { "hb:" + it.id }) { ShowResult(it, "Hörbuch", onShowClick) }
    }
    // official YouTube channels already come with the Hörspiel/Hörbuch results
    val shown = (uiState.hoerspielResults + uiState.hoerbuchResults).map { it.id }.toSet()
    val youtubeShows = uiState.youtubeShows.filter { it.id !in shown }
    if (youtubeShows.isNotEmpty()) {
        item(key = "h:yts") { AllHeader("Reihen & Kanäle auf YouTube") }
        items(youtubeShows, key = { "yts:" + it.id }) { show ->
            val channel = show.id.removePrefix("yt:").takeIf { it.startsWith("UC") }
            if (channel != null) {
                // a channel opens as an artist page: videos, playlists, albums
                ShowResult(show, "Kanal") {
                    onArtistClick(dev.schlubbe.musicagent.data.repository.youTubeChannelArtist(channel, show.title, show.imageUrl))
                }
            } else {
                ShowResult(show, "Reihe", onShowClick)
            }
        }
    }
    if (uiState.youtubeAlbums.isNotEmpty()) {
        item(key = "h:yta") { AllHeader("Alben auf YouTube") }
        items(uiState.youtubeAlbums, key = { "yta:" + it.id }) { ShowResult(it, "Album", onShowClick) }
    }
    if (uiState.youtubeResults.isNotEmpty()) {
        item(key = "h:ytv") { AllHeader("Einzelne Videos") }
        items(uiState.youtubeResults, key = { "ytv:" + it.sourceId }) { TrackResult(it, uiState, onTrackClick, trackActions) }
    }
    val empty = uiState.hoerspielResults.isEmpty() && uiState.hoerbuchResults.isEmpty() &&
        uiState.youtubeShows.isEmpty() && uiState.youtubeAlbums.isEmpty() && uiState.youtubeResults.isEmpty()
    if (uiState.spokenPending > 0) {
        item(key = "spoken:loading") {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Canopy.accent, modifier = Modifier.size(28.dp))
            }
        }
    } else if (empty) {
        item(key = "spoken:none") {
            Text(
                "Nichts gefunden. Viele bekannte Hörbücher und Hörspiele gibt es nur zu kaufen " +
                    "(Audible, Spotify, Hörverlag) und deshalb nicht hier.",
                style = MaterialTheme.typography.bodyMedium,
                color = Canopy.neutral500,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }
    }
    if (!uiState.youtubeEnabled && uiState.spokenPending == 0) {
        item(key = "spoken:yt") {
            Column(
                modifier = Modifier
                    .padding(top = 18.dp)
                    .fillMaxWidth()
                    .clip(CanopyShapes.small)
                    .background(Canopy.surface)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Auch auf YouTube suchen?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Canopy.text)
                Text(
                    "Dort gibt es viele ganze Hörspiele und Hörbücher. Die App lädt dann Daten von YouTube; ausschalten geht in den Einstellungen unter Audio-Quellen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Canopy.neutral500,
                )
                CanopyButton(text = "YouTube einschalten", onClick = onEnableYouTube, variant = CanopyButtonVariant.Secondary)
            }
        }
    }
}

private const val ALL_SECTION_SIZE = 3
private const val ALL_TRACKS = 4

private fun matchesQuery(name: String, query: String): Boolean {
    val a = name.lowercase().filter(Char::isLetterOrDigit)
    val b = query.lowercase().filter(Char::isLetterOrDigit)
    return b.isNotEmpty() && (a == b || a.startsWith(b) || b.startsWith(a) && a.length >= 3)
}

@Composable
private fun AllHeader(title: String, onShowAll: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Canopy.text,
            modifier = Modifier.weight(1f),
        )
        if (onShowAll != null) {
            Text(
                "Alle anzeigen",
                style = MaterialTheme.typography.labelLarge,
                color = Canopy.text,
                modifier = Modifier
                    .clip(CanopyPillShape)
                    .background(Canopy.surface)
                    .clickable(onClick = onShowAll)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun ArtistResult(artist: ArtistResultDto, onClick: (ArtistResultDto) -> Unit) {
    ResultRow(
        title = artist.name,
        subtitle = listOfNotNull(
            "Künstler",
            sourceLabel(artist.source),
            artist.subscriberCount?.takeIf { it.isNotBlank() },
        ).joinToString(" · "),
        thumbnailUrl = artist.thumbnailUrl,
        seed = artist.name,
        circular = true,
        onClick = { onClick(artist) },
    )
}

@Composable
private fun PlaylistResult(playlist: PlaylistResultDto, onClick: (source: String, sourceId: String) -> Unit) {
    ResultRow(
        title = playlist.title,
        subtitle = listOfNotNull("Playlist", sourceLabel(playlist.source)).joinToString(" · "),
        thumbnailUrl = playlist.thumbnailUrl,
        seed = playlist.title,
        onClick = { onClick(playlist.source, playlist.sourceId) },
    )
}

@Composable
private fun AlbumResult(album: AlbumResultDto, onClick: (source: String, sourceId: String) -> Unit) {
    ResultRow(
        title = album.title,
        subtitle = listOfNotNull(album.artist, "Album", sourceLabel(album.source)).joinToString(" · "),
        thumbnailUrl = album.thumbnailUrl,
        seed = album.title,
        // Albums open through the same remote-playlist browse screen
        // (an album *is* a playlist with is_album=true upstream).
        onClick = { onClick(album.source, album.sourceId) },
    )
}

@Composable
private fun ShowResult(show: dev.schlubbe.musicagent.podcast.PodcastShow, kind: String, onClick: (String) -> Unit) {
    ResultRow(
        title = show.title,
        subtitle = listOfNotNull(kind, show.author, show.episodeCount?.takeIf { it > 0 }?.let { if (it == 1) "1 Folge" else "$it Folgen" }).joinToString(" · "),
        thumbnailUrl = show.imageUrl,
        seed = show.title,
        onClick = { onClick(show.id) },
    )
}

@Composable
private fun TrackResult(
    track: TrackResultDto,
    uiState: SearchUiState,
    onTrackClick: (TrackResultDto) -> Unit,
    trackActions: TrackActions,
) {
    val key = "${track.source}:${track.sourceId}"
    val download = uiState.downloadStates[key]
    var menuExpanded by remember { mutableStateOf(false) }
    ResultRow(
        title = track.title,
        subtitle = listOfNotNull(
            track.artist,
            sourceLabel(track.source),
            track.durationSec?.takeIf { it > 0 }?.let {
                if (it >= 3600) "%d:%02d:%02d".format(it / 3600, it / 60 % 60, it % 60) else "%d:%02d".format(it / 60, it % 60)
            },
        ).joinToString(" · "),
        thumbnailUrl = track.thumbnailUrl,
        seed = track.title,
        state = stateFor(track, download),
        onClick = { onTrackClick(track) },
        onLongClick = { menuExpanded = true },
        trailing = {
            Box {
                CanopyIconButton(
                    icon = phosphorIcon("dots-three-vertical"),
                    onClick = { menuExpanded = true },
                    size = 36.dp,
                    contentDescription = "Weitere Optionen",
                )
                TrackActionsMenu(
                    expanded = menuExpanded,
                    onDismiss = { menuExpanded = false },
                    track = track,
                    isLiked = key in uiState.likedTrackIds,
                    download = download,
                    actions = trackActions,
                )
            }
        },
    )
}

/** A "Zuletzt gesucht" chip: taps re-run that query, and a long-press surfaces a
 * one-item "Löschen" menu (a plain [DropdownMenu] anchored to the chip, opened via
 * `combinedClickable`'s `onLongClick` - the same trigger Android's own launcher/
 * settings use for "remove this" on a small tappable chip, where there's no room
 * for a persistent delete affordance and a swipe gesture would collide with the
 * row's horizontal wrap). Not built on [CanopyChip] itself, since that component
 * is shared by plenty of other chips (mood/genre filters) that have no delete
 * concept at all - this is deliberately its own composable rather than a new
 * optional parameter on a shared one. */
@Composable
private fun HistorySearchChip(
    label: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .clip(CanopyPillShape)
                .background(Canopy.surface)
                .border(1.dp, Canopy.divider, CanopyPillShape)
                .combinedClickable(onClick = onClick, onLongClick = { menuExpanded = true })
                .padding(horizontal = 18.dp, vertical = 9.dp),
        ) {
            Text(
                text = label,
                color = Canopy.text,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            )
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text("Löschen") },
                leadingIcon = { Icon(phosphorIcon("trash"), contentDescription = null, tint = Canopy.accent2) },
                onClick = {
                    menuExpanded = false
                    onDelete()
                },
            )
        }
    }
}

private class TrackActions(
    val onAnalyze: (TrackResultDto) -> Unit,
    val onLikeToggle: (TrackResultDto) -> Unit,
    val onDownload: (TrackResultDto) -> Unit,
    val onAddToQueue: (TrackResultDto) -> Unit,
    val onAddToPlaylist: (TrackResultDto) -> Unit,
    val onArtist: (TrackResultDto) -> Unit,
    val onShare: (TrackResultDto) -> Unit,
)

@Composable
private fun TrackActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    track: TrackResultDto,
    isLiked: Boolean,
    download: DownloadState?,
    actions: TrackActions,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        @Composable
        fun item(label: String, icon: String, enabled: Boolean = true, filled: Boolean = false, action: () -> Unit) =
            DropdownMenuItem(
                text = { Text(label) },
                leadingIcon = { Icon(phosphorIcon(icon, filled = filled), contentDescription = null, tint = Canopy.accent) },
                enabled = enabled,
                onClick = { onDismiss(); action() },
            )
        item(if (isLiked) "Nicht mehr gefällt mir" else "Gefällt mir", "heart", filled = isLiked) { actions.onLikeToggle(track) }
        item("Zur Warteschlange hinzufügen", "list-plus") { actions.onAddToQueue(track) }
        item("Zu Playlist hinzufügen", "plus-circle") { actions.onAddToPlaylist(track) }
        when {
            download == DownloadState.COMPLETED -> {
                item("Offline verfügbar", "check-circle", enabled = false) {}
                if (!BuildConfig.DAWN) item(ANALYZE_LABEL, "waveform") { actions.onAnalyze(track) }
            }
            download == DownloadState.DOWNLOADING || download == DownloadState.QUEUED ->
                item("Wird heruntergeladen …", "download-simple", enabled = false) {}
            else -> item("Herunterladen", "download-simple") { actions.onDownload(track) }
        }
        if (!track.artist.isNullOrBlank()) item("Zum Künstler", "user-circle") { actions.onArtist(track) }
        item("Teilen", "share-network") { actions.onShare(track) }
    }
}

/** One result row: 48dp cover, title/subtitle, the optional state pill and trailing action. */
@Composable
private fun ResultRow(
    title: String,
    subtitle: String,
    thumbnailUrl: String?,
    seed: String,
    onClick: () -> Unit,
    circular: Boolean = false,
    state: ResultState? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CanopyShapes.small)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 6.dp, end = if (trailing != null) 0.dp else 6.dp, top = 9.dp, bottom = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (circular) {
            if (thumbnailUrl != null) {
                TrackThumbnail(url = thumbnailUrl, size = 48.dp, seed = seed, modifier = Modifier.clip(CircleShape))
            } else {
                CanopyAvatar(initials = seed, size = 48.dp)
            }
        } else {
            TrackThumbnail(url = thumbnailUrl, size = 48.dp, seed = seed)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = Canopy.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Canopy.neutral500,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        state?.let { StatePill(it) }
        trailing?.invoke()
    }
}

/** The per-result state pill. "Alert" states (DRM, in-flight download) take the
 * coral treatment; settled states are neutral. Plain streamable results get no
 * pill at all - "Stream" on every row carried no information. */
private data class ResultState(val label: String, val iconName: String, val alert: Boolean)

private fun stateFor(track: TrackResultDto, download: DownloadState?): ResultState? = when {
    // Not fully playable from SoundCloud (DRM or 30s preview) - playback and
    // downloads use the matching YouTube Music recording instead.
    track.isDrmProtected && download == null && !BuildConfig.DAWN -> ResultState("via YouTube", "youtube-logo", alert = false)
    download == DownloadState.DOWNLOADING || download == DownloadState.QUEUED ->
        ResultState("Lädt", "download-simple", alert = true)
    download == DownloadState.FAILED -> ResultState("Fehler", "warning-circle", alert = true)
    download == DownloadState.COMPLETED -> ResultState("Offline", "check-circle", alert = false)
    else -> null
}

@Composable
private fun StatePill(state: ResultState) {
    val fg = if (state.alert) Canopy.accent2 else Canopy.neutral500
    val bg = if (state.alert) Canopy.accent2_100 else Canopy.neutral200
    Row(
        modifier = Modifier
            .clip(CanopyPillShape)
            .background(bg)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            phosphorIcon(state.iconName),
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(12.dp),
        )
        Text(
            state.label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (state.alert) FontWeight.Bold else FontWeight.Normal,
            ),
            color = fg,
        )
    }
}

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Where a result comes from - not shown in Dawn Music, where a song is just a song. */
private fun sourceLabel(source: String): String? = when {
    source == "podcast" -> "YouTube" // only YouTube videos as audio show up as tracks here
    BuildConfig.DAWN -> null
    source == "soundcloud" -> "SoundCloud"
    else -> "YT Music"
}
