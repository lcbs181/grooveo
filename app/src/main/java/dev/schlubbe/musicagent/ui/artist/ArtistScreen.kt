package dev.schlubbe.musicagent.ui.artist
import dev.schlubbe.musicagent.ui.theme.dawnContainer
import dev.schlubbe.musicagent.ui.theme.LocalChromeInset
import dev.schlubbe.musicagent.ui.components.AnalyzeMenuItem
import dev.schlubbe.musicagent.ui.components.LocalTrackAnalyzer

import android.widget.Toast
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import dev.schlubbe.musicagent.data.remote.dto.AlbumResultDto
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import dev.schlubbe.musicagent.data.remote.dto.ArtistResultDto
import dev.schlubbe.musicagent.BuildConfig
import dev.schlubbe.musicagent.data.remote.dto.ArtistDetailDto
import dev.schlubbe.musicagent.data.remote.dto.PlaylistResultDto
import dev.schlubbe.musicagent.data.remote.dto.TrackResultDto
import dev.schlubbe.musicagent.ui.components.CanopyAvatar
import dev.schlubbe.musicagent.ui.components.CanopyBadge
import dev.schlubbe.musicagent.ui.components.CanopyBadgeTone
import dev.schlubbe.musicagent.ui.components.CanopyButton
import dev.schlubbe.musicagent.ui.components.CanopyButtonVariant
import dev.schlubbe.musicagent.ui.components.CanopyIconButton
import dev.schlubbe.musicagent.ui.components.CanopySectionHeader
import dev.schlubbe.musicagent.ui.components.DrmLockIcon
import dev.schlubbe.musicagent.ui.components.LocalCanopyOverlay
import dev.schlubbe.musicagent.ui.components.PullRefresh
import dev.schlubbe.musicagent.ui.components.TrackThumbnail
import dev.schlubbe.musicagent.ui.components.WaveSweep
import dev.schlubbe.musicagent.ui.components.rememberFadeUp
import dev.schlubbe.musicagent.ui.components.rememberHeartPopScale
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.playlist.AddToPlaylistDialog
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.theme.CanopyShapes
import dev.schlubbe.musicagent.ui.util.shareText

private const val SHELF_PREVIEW_COUNT = 5
private const val SHELF_CARD_SIZE = 140

// GrooveoApp.dc.html lines 499/513: a 170px banner with the header column
// pulled up -46px over it, so the avatar overlaps the gradient. Both are
// design-fixed sizes, not derived from any data.
private val BANNER_HEIGHT = 170.dp
private val HEADER_OVERLAP = 46.dp
private val HERO_HEIGHT = 300.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(
    source: String,
    sourceId: String,
    onTrackSelected: () -> Unit,
    onFollowersSelected: () -> Unit,
    onPlaylistSelected: (source: String, sourceId: String) -> Unit,
    onNavigateBack: () -> Unit,
    onArtistSelected: (source: String, sourceId: String) -> Unit = { _, _ -> },
    viewModel: ArtistViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    // Keyed on the artist identity, not just remembered bare -- otherwise
    // navigating from one artist to another while this screen's composable
    // instance is reused (same route pattern) would leave a stale expanded/
    // collapsed state from the PREVIOUS artist's shelf sizes hanging around.
    // This is exactly the kind of state-hoisting bug that can look like "show
    // more stopped working" if it flips to the wrong artist mid-navigation.
    var showAllTop by remember(source, sourceId) { mutableStateOf(false) }
    var showAllLatest by remember(source, sourceId) { mutableStateOf(false) }
    var showTopMenu by remember(source, sourceId) { mutableStateOf(false) }
    val context = LocalContext.current
    val overlay = LocalCanopyOverlay.current

    LaunchedEffect(source, sourceId) {
        viewModel.load(source, sourceId)
    }
    LaunchedEffect(uiState.navigateToFollowers) {
        if (uiState.navigateToFollowers) {
            onFollowersSelected()
            viewModel.onFollowersNavigated()
        }
    }

    Scaffold(containerColor = dawnContainer, contentWindowInsets = WindowInsets(0)) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // The design's back/share/overflow buttons float on the banner itself
            // (see ArtistHeader below) and only exist once the artist has loaded.
            // This fallback bar is what the old plain top-of-screen row is kept for:
            // without it, a loading spinner or an error message would stealth the
            // back button entirely, which the design's own mockup never has to
            // consider since it doesn't model a loading state.
            if (uiState.artist == null) {
                Row(modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 10.dp)) {
                    CanopyIconButton(icon = phosphorIcon("arrow-left"), onClick = onNavigateBack, iconSize = 20.dp, contentDescription = "Zurück")
                }
            }
            when {
                uiState.isLoading -> CircularProgressIndicator(modifier = Modifier.padding(16.dp), color = Canopy.accent)
                uiState.error != null -> Column(modifier = Modifier.padding(16.dp)) {
                    Text(uiState.error!!, color = MaterialTheme.colorScheme.error)
                    CanopyButton(
                        text = "Erneut versuchen",
                        onClick = { viewModel.retry() },
                        variant = CanopyButtonVariant.Secondary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                uiState.artist != null -> {
                    val artist = uiState.artist!!
                    val topPreview = artist.topTracks.take(if (showAllTop) artist.topTracks.size else SHELF_PREVIEW_COUNT)
                    val latestPreview = artist.latestTracks.take(
                        if (showAllLatest) artist.latestTracks.size else SHELF_PREVIEW_COUNT,
                    )

                    PullRefresh(onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
                        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalChromeInset.current)) {
                            item {
                                ArtistHeader(
                                    artist = artist,
                                    isFollowing = uiState.isFollowing,
                                    onNavigateBack = onNavigateBack,
                                    onShare = { context.shareText(artist.webpageUrl) },
                                    showOverflowMenu = showTopMenu,
                                    onOverflowMenuChange = { showTopMenu = it },
                                    onFollowToggle = {
                                        // The design fires its full follow-spray confetti the
                                        // moment "Folgen" is tapped, not after the toggle
                                        // resolves -- so the spray only plays on the
                                        // not-following -> following transition, never on unfollow.
                                        if (!uiState.isFollowing) overlay.spray()
                                        viewModel.toggleFollow()
                                    },
                                    onShuffle = {
                                        val queue = (artist.topTracks + artist.latestTracks).shuffled()
                                        queue.firstOrNull()?.let { track ->
                                            viewModel.onTrackClicked(track, queue)
                                            onTrackSelected()
                                        }
                                    },
                                    onPlayFirst = {
                                        val queue = artist.topTracks.ifEmpty { artist.latestTracks }
                                        queue.firstOrNull()?.let { track ->
                                            viewModel.onTrackClicked(track, queue)
                                            onTrackSelected()
                                        }
                                    },
                                )

                                if (artist.topTracks.isNotEmpty()) {
                                    CanopySectionHeader(
                                        title = "Top-Titel",
                                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).then(rememberFadeUp()),
                                        action = shelfActionLabel(artist.topTracks.size, showAllTop),
                                        onActionClick = { showAllTop = !showAllTop },
                                    )
                                }
                            }
                            items(topPreview, key = { "top:${it.source}:${it.sourceId}" }) { track ->
                                ArtistTrackRow(
                                    track = track,
                                    pageArtist = artist.name,
                                    thumbnailSize = 52.dp,
                                    isLiked = "${track.source}:${track.sourceId}" in uiState.likedTrackIds,
                                    onClick = {
                                        viewModel.onTrackClicked(track, artist.topTracks)
                                        onTrackSelected()
                                    },
                                    onLikeClick = { viewModel.onLikeToggled(track) },
                                    onAddToPlaylistClick = { viewModel.onAddToPlaylistClicked(track) },
                                    onAddToQueueClick = { viewModel.onAddToQueueClicked(track) },
                                    onDownloadClick = { viewModel.onDownloadClicked(track) },
                                )
                            }

                            latestRelease(artist)?.let { release ->
                                item {
                                    CanopySectionHeader(
                                        title = "Neueste Veröffentlichung",
                                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp).then(rememberFadeUp()),
                                    )
                                    LatestReleaseRow(release) { onPlaylistSelected(release.source, release.sourceId) }
                                }
                            }

                            if (artist.latestTracks.isNotEmpty()) {
                                item {
                                    CanopySectionHeader(
                                        title = "Neueste Titel",
                                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).then(rememberFadeUp()),
                                        action = shelfActionLabel(artist.latestTracks.size, showAllLatest),
                                        onActionClick = { showAllLatest = !showAllLatest },
                                    )
                                }
                            }
                            items(latestPreview, key = { "latest:${it.source}:${it.sourceId}" }) { track ->
                                ArtistTrackRow(
                                    track = track,
                                    pageArtist = artist.name,
                                    thumbnailSize = 46.dp,
                                    isLiked = "${track.source}:${track.sourceId}" in uiState.likedTrackIds,
                                    onClick = {
                                        viewModel.onTrackClicked(track, artist.latestTracks)
                                        onTrackSelected()
                                    },
                                    onLikeClick = { viewModel.onLikeToggled(track) },
                                    onAddToPlaylistClick = { viewModel.onAddToPlaylistClicked(track) },
                                    onAddToQueueClick = { viewModel.onAddToQueueClicked(track) },
                                    onDownloadClick = { viewModel.onDownloadClicked(track) },
                                )
                            }

                            val openList = { item: ArtistShelfItem -> onPlaylistSelected(item.source, item.sourceId) }
                            if (artist.albums.isNotEmpty()) {
                                item {
                                    ArtistPlaylistShelf(
                                        // Pages without separate singles mix both in one list.
                                        title = if (artist.singles.isEmpty()) "Alben & Singles" else "Alben",
                                        items = artist.albums.map { it.toShelfItem() },
                                        onClick = openList,
                                    )
                                }
                            }
                            if (artist.singles.isNotEmpty()) {
                                item { ArtistPlaylistShelf("Singles & EPs", artist.singles.map { it.toShelfItem() }, openList) }
                            }
                            if (artist.videos.isNotEmpty()) {
                                item {
                                    ArtistPlaylistShelf(
                                        title = "Musikvideos",
                                        items = artist.videos.map { it.toShelfItem() },
                                        onClick = { item ->
                                            artist.videos.firstOrNull { it.sourceId == item.sourceId }?.let { video ->
                                                viewModel.onTrackClicked(video, artist.videos)
                                                onTrackSelected()
                                            }
                                        },
                                    )
                                }
                            }
                            if (artist.featuredOn.isNotEmpty()) {
                                item { ArtistPlaylistShelf("Zu hören in", artist.featuredOn.map { it.toShelfItem() }, openList) }
                            }
                            if (artist.playlists.isNotEmpty()) {
                                item {
                                    ArtistPlaylistShelf(
                                        title = if (artist.source == "ytmusic") "Playlists von ${artist.name}" else "Playlists",
                                        items = artist.playlists.map { it.toShelfItem() },
                                        onClick = openList,
                                    )
                                }
                            }
                            if (artist.related.isNotEmpty()) {
                                item {
                                    ArtistPlaylistShelf(
                                        title = "Fans mögen auch",
                                        items = artist.related.map { it.toShelfItem() },
                                        onClick = { item -> onArtistSelected(item.source, item.sourceId) },
                                        circle = true,
                                    )
                                }
                            }
                            artist.description?.takeIf { it.isNotBlank() }?.let { description ->
                                item {
                                    CanopySectionHeader(
                                        title = "Über ${artist.name}",
                                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp).then(rememberFadeUp()),
                                    )
                                    ArtistBio(description)
                                }
                            }

                            // The design also has a "Folgt" (who this artist follows) row next
                            // to Follower, and both an "Im Spotlight gepinnt" shelf and an
                            // "Ähnliche Künstler" shelf above/below the track lists. None of
                            // those have any backing field on ArtistDetailDto or a repository
                            // call this screen can reach, so they're omitted rather than wired
                            // to fabricated content -- see the task report for the full list.
                            if (artist.source == "soundcloud") {
                                item {
                                    ArtistListRow(
                                        label = "Follower",
                                        icon = phosphorIcon("user"),
                                        onClick = viewModel::onFollowersClicked,
                                        modifier = Modifier.padding(top = 8.dp).then(rememberFadeUp()),
                                    )
                                }
                            }
                        }
                    }
                }
            }
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
}

/** "Alle anzeigen" / "Weniger anzeigen" for a shelf's [CanopySectionHeader] action slot,
 * or null to hide the action entirely when the shelf is too short to need collapsing. */
private fun shelfActionLabel(total: Int, expanded: Boolean): String? =
    if (total > SHELF_PREVIEW_COUNT) (if (expanded) "Weniger anzeigen" else "Alle anzeigen") else null

/** Common shape for the "Alben & Singles" and "Playlists" shelves below - an album
 * and a playlist search result carry almost the same fields, but not a shared type
 * (see AlbumResultDto/PlaylistResultDto), so each is mapped down to just what the
 * shelf card needs to render. [subtitle] is the album's artist or the playlist's
 * owner - whichever the source DTO actually has. */
private data class ArtistShelfItem(
    val source: String,
    val sourceId: String,
    val title: String,
    val subtitle: String?,
    val thumbnailUrl: String?,
)

private fun AlbumResultDto.toShelfItem() = ArtistShelfItem(
    source, sourceId, title,
    listOfNotNull(year, kind).joinToString(" · ").ifEmpty { artist.orEmpty() }.ifEmpty { null },
    thumbnailUrl,
)
private fun PlaylistResultDto.toShelfItem() = ArtistShelfItem(source, sourceId, title, owner, thumbnailUrl)
private fun TrackResultDto.toShelfItem() = ArtistShelfItem(source, sourceId, title, plays ?: artist, thumbnailUrl)
private fun ArtistResultDto.toShelfItem() = ArtistShelfItem(source, sourceId, name, null, thumbnailUrl)

/** The newest album or single, like Spotify's "Neueste Veröffentlichung". YouTube
 * Music lists both newest first, so on a tie in the year the single wins. */
private fun latestRelease(artist: ArtistDetailDto): AlbumResultDto? =
    (artist.singles.take(1) + artist.albums.take(1))
        .filter { it.year != null }
        .maxByOrNull { it.year!!.toIntOrNull() ?: 0 }

@Composable
private fun LatestReleaseRow(release: AlbumResultDto, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .then(rememberFadeUp()),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackThumbnail(url = release.thumbnailUrl, size = 84.dp, seed = release.title)
        Column(modifier = Modifier.weight(1f)) {
            Text(release.title, style = MaterialTheme.typography.titleMedium, color = Canopy.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(release.year, release.kind).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Canopy.neutral500,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** A horizontal cover-card shelf, same layout as Home's shelves (TrackThumbnail +
 * title + subtitle in a fixed-width column) - reused here instead of a vertical list
 * since both album/playlist tabs on the design's reference mockups scroll sideways. */
@Composable
private fun ArtistPlaylistShelf(
    title: String,
    items: List<ArtistShelfItem>,
    onClick: (ArtistShelfItem) -> Unit,
    circle: Boolean = false,
) {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        CanopySectionHeader(
            title = title,
            modifier = Modifier.padding(horizontal = 20.dp).then(rememberFadeUp()),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { "${it.source}:${it.sourceId}" }) { shelfItem ->
                Column(
                    modifier = Modifier
                        .width(SHELF_CARD_SIZE.dp)
                        .clickable { onClick(shelfItem) },
                ) {
                    if (circle) {
                        CanopyAvatar(
                            initials = shelfItem.title,
                            size = SHELF_CARD_SIZE.dp,
                            content = shelfItem.thumbnailUrl?.let { url ->
                                { AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            },
                        )
                    } else {
                        TrackThumbnail(url = shelfItem.thumbnailUrl, size = SHELF_CARD_SIZE.dp, seed = shelfItem.title)
                    }
                    Text(
                        shelfItem.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = Canopy.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = if (circle) TextAlign.Center else TextAlign.Start,
                        modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                    )
                    shelfItem.subtitle?.let { subtitle ->
                        Text(
                            subtitle,
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
}

/** Banner + avatar + follow controls: GrooveoApp.dc.html lines 498-533.
 *
 * The banner itself falls back to Canopy's accent gradient when the artist has
 * no [ArtistDetailDto.bannerUrl] -- the design never wires a real photo here
 * either, so the gradient is the intended look, not a placeholder standing in
 * for missing art. */
@Composable
private fun ArtistHeader(
    artist: ArtistDetailDto,
    isFollowing: Boolean,
    onNavigateBack: () -> Unit,
    onShare: () -> Unit,
    showOverflowMenu: Boolean,
    onOverflowMenuChange: (Boolean) -> Unit,
    onFollowToggle: () -> Unit,
    onShuffle: () -> Unit,
    onPlayFirst: () -> Unit,
) {
    val hasTracks = artist.topTracks.isNotEmpty() || artist.latestTracks.isNotEmpty()
    // YouTube Music's header image is a real artist photo: shown big with the
    // name on it, like Spotify. Channel banners are decorative strips and keep the
    // banner + avatar layout.
    val hero = artist.source == "ytmusic" && artist.monthlyListeners != null && artist.bannerUrl != null

    Box(modifier = Modifier.fillMaxWidth().then(rememberFadeUp())) {
        if (hero) {
            Box(modifier = Modifier.fillMaxWidth().height(HERO_HEIGHT)) {
                AsyncImage(
                    model = artist.bannerUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.65f)),
                    ),
                )
                Text(
                    artist.name,
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                )
            }
        } else if (artist.bannerUrl != null) {
            AsyncImage(
                model = artist.bannerUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(BANNER_HEIGHT),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(BANNER_HEIGHT)
                    .background(Brush.linearGradient(listOf(Canopy.accent400, Canopy.accent2, Canopy.accent800))),
            )
        }

        FloatingBannerButton(
            icon = phosphorIcon("arrow-left"),
            onClick = onNavigateBack,
            contentDescription = "Zurück",
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
        )
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FloatingBannerButton(icon = phosphorIcon("share-network"), onClick = onShare, contentDescription = "Teilen")
            Box {
                // "ph-dots-three-outline-vertical" in the markup isn't in
                // PhosphorIcon.kt's map (falls through to WarningCircle), so this
                // uses the mapped "dots-three-vertical" glyph instead. Its menu
                // only holds Teilen -- the design's own overflow target
                // ("openPlayerMenu") is a player-context sheet with no artist-level
                // equivalent in ArtistViewModel, so nothing else is invented here.
                FloatingBannerButton(
                    icon = phosphorIcon("dots-three-vertical"),
                    onClick = { onOverflowMenuChange(true) },
                    contentDescription = "Mehr",
                )
                DropdownMenu(expanded = showOverflowMenu, onDismissRequest = { onOverflowMenuChange(false) }) {
                    DropdownMenuItem(
                        text = { Text("Teilen") },
                        leadingIcon = { Icon(phosphorIcon("share-network"), contentDescription = null, tint = Canopy.accent) },
                        onClick = { onOverflowMenuChange(false); onShare() },
                    )
                }
            }
        }

        Column(
            modifier = Modifier.padding(
                top = if (hero) HERO_HEIGHT + 4.dp else BANNER_HEIGHT - HEADER_OVERLAP,
                start = 16.dp,
                end = 16.dp,
            ),
        ) {
            if (!hero) Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                // The 4dp ring is the background colour, not an accent stroke -- a
                // moat rather than a highlight -- so this wraps CanopyAvatar in a
                // bg-filled circle instead of passing ring=true (which draws a 2dp
                // *accent* border baked into the component).
                Box(modifier = Modifier.clip(CircleShape).background(Canopy.bg).padding(4.dp)) {
                    CanopyAvatar(
                        initials = artist.name,
                        size = 96.dp,
                        content = artist.thumbnailUrl?.let { url ->
                            {
                                AsyncImage(
                                    model = url,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        },
                    )
                }
                // Dawn Music does not talk about sources (see also Search).
                if (!BuildConfig.DAWN) CanopyBadge(
                    text = if (artist.source == "soundcloud") "SoundCloud" else "YT Music",
                    tone = CanopyBadgeTone.Accent,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            // The design pairs the name with a `ph-fill ph-seal-check` verified
            // badge, but ArtistDetailDto carries no verification flag at all, so
            // there's no way to tell a verified artist from any other -- showing it
            // unconditionally would be exactly the kind of invented signal the task
            // warns against. Omitted rather than shown for everyone.
            if (!hero) Text(
                artist.name,
                style = MaterialTheme.typography.headlineLarge,
                color = Canopy.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 14.dp),
            )

            // The design's line reads "48.312 Follower - 55 Folgend"; ArtistDetailDto
            // only carries subscriberCount, so the "Folgend" (following) half is
            // dropped rather than invented -- same precedent as Home's DailyPickCard,
            // which replaced a hardcoded like count with real metadata instead of
            // faking one.
            (artist.monthlyListeners?.let { "$it monatliche Hörer*innen" } ?: artist.subscriberCount?.let { "$it Follower" })?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = Canopy.neutral500,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CanopyButton(
                    text = if (isFollowing) "Gefolgt" else "Folgen",
                    onClick = onFollowToggle,
                    variant = if (isFollowing) CanopyButtonVariant.Secondary else CanopyButtonVariant.Primary,
                )
                Spacer(modifier = Modifier.weight(1f))
                CanopyIconButton(
                    icon = phosphorIcon("shuffle"),
                    onClick = onShuffle,
                    variant = CanopyButtonVariant.Secondary,
                    size = 44.dp,
                    enabled = hasTracks,
                    contentDescription = "Zufällig abspielen",
                )
                // A hand-rolled button rather than CanopyIconButton: the design
                // itself drops down to a raw <button> here too (line 531 of the
                // markup) instead of its own IconButton import, because this is the
                // one accent-filled 56dp FAB on the screen with accent-900 (dark)
                // icon-on-accent contrast -- CanopyIconButton's Primary variant
                // always uses white content, which isn't available to override
                // without editing CanopyControls.kt.
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Canopy.accent)
                        .clickable(enabled = hasTracks, onClick = onPlayFirst)
                        .alpha(if (hasTracks) 1f else 0.5f),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        phosphorIcon("play", filled = true),
                        contentDescription = "Abspielen",
                        tint = Canopy.onAccent,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

/** One of the banner's floating back/share/overflow buttons: a 40dp circle on a
 * `rgba(0,0,0,.42)` blurred backdrop (blur itself is a no-op here -- Compose has
 * no cheap backdrop-blur primitive, so this matches the design's darkening
 * without the frosted-glass softening). Bypasses [CanopyIconButton] because its
 * three variants are all theme-colored, while these buttons need to read the
 * same white-on-dark-scrim regardless of light/dark theme, matching every other
 * banner in the app (Player, Home, PlaylistDetail) that scrims an image the
 * same way. */
@Composable
private fun FloatingBannerButton(
    icon: ImageVector,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.42f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

private const val BIO_COLLAPSED_LINES = 3

/** Collapsed to [BIO_COLLAPSED_LINES] lines with a "Mehr anzeigen" toggle - shown
 * only if the bio actually overflows at that height (detected via onTextLayout's
 * hasVisualOverflow, not just "is the text long"), so a short bio never grows a
 * pointless toggle it wouldn't need. */
@Composable
private fun ArtistBio(description: String) {
    var expanded by remember { mutableStateOf(false) }
    var isOverflowing by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).then(rememberFadeUp())) {
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = Canopy.neutral600,
            maxLines = if (expanded) Int.MAX_VALUE else BIO_COLLAPSED_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result ->
                if (!expanded && result.hasVisualOverflow) isOverflowing = true
            },
        )
        if (isOverflowing || expanded) {
            Text(
                if (expanded) "Weniger anzeigen" else "Mehr anzeigen",
                color = Canopy.accent,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable { expanded = !expanded },
            )
        }
    }
}

/** A navigable row (label + trailing chevron): GrooveoApp.dc.html's `ListRow`
 * for Follower/Folgt at the foot of the screen. Only "Follower" is wired up --
 * the design also has a "Folgt" row (who this artist follows), but nothing in
 * SearchRepository or ArtistViewModel exposes that list for either source, so
 * it's left out rather than pointed at a screen that doesn't exist. */
@Composable
private fun ArtistListRow(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Canopy.neutral500, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = Canopy.text, modifier = Modifier.weight(1f))
        Icon(phosphorIcon("caret-right"), contentDescription = null, tint = Canopy.neutral400, modifier = Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtistTrackRow(
    track: TrackResultDto,
    pageArtist: String,
    thumbnailSize: Dp,
    isLiked: Boolean,
    onClick: () -> Unit,
    onLikeClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onAddToQueueClick: () -> Unit,
    onDownloadClick: () -> Unit,
) {
    val analyzer = LocalTrackAnalyzer.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var sweepTrigger by remember { mutableIntStateOf(0) }
    var likeTrigger by remember { mutableIntStateOf(0) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onAddToQueueClick()
                Toast.makeText(context, "Zur Warteschlange hinzugefügt", Toast.LENGTH_SHORT).show()
            }
            false
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            // only while swiping: rows are transparent in Dawn Music, so a
            // permanent backdrop would show through every row
            if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Canopy.accent800)
                    .padding(start = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(phosphorIcon("list-plus"), contentDescription = "Zur Warteschlange hinzufügen", tint = Canopy.accent100)
            }
        },
    ) {
        var menuExpanded by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(dawnContainer)
                .clickable {
                    sweepTrigger++
                    onClick()
                }
                .padding(vertical = 9.dp, horizontal = 6.dp),
        ) {
            WaveSweep(trigger = sweepTrigger, shape = CanopyShapes.small)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackThumbnail(track.thumbnailUrl, size = thumbnailSize, seed = track.title)
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            track.title,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (track.isDrmProtected) DrmLockIcon()
                    }
                    Row(
                        modifier = Modifier.padding(top = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            // Spotify shows play counts here; otherwise the artist,
                            // spelled like the page (uploads sometimes say "KRAFTKLUB").
                            track.plays ?: track.artist?.let { if (it.equals(pageArtist, ignoreCase = true)) pageArtist else it }
                                ?: pageArtist,
                            style = MaterialTheme.typography.bodySmall,
                            color = Canopy.neutral500,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        formatTrackDuration(track.durationSec)?.let { duration ->
                            Text(duration, style = MaterialTheme.typography.labelSmall, color = Canopy.neutral500)
                        }
                        // The design's meta line also carries a play count and a
                        // playing/downloaded "state" icon here; TrackResultDto has
                        // no play-count field and no download-state join reaches
                        // this screen, so per the no-invented-data rule the like
                        // heart takes that slot instead -- the same substitution
                        // PlaylistDetailScreen's row already made for the same gap.
                        val popScale = rememberHeartPopScale(likeTrigger)
                        Icon(
                            phosphorIcon("heart", filled = isLiked),
                            contentDescription = "Gefällt mir",
                            tint = if (isLiked) Canopy.like else Canopy.neutral500,
                            modifier = Modifier
                                .size(14.dp)
                                .graphicsLayer { scaleX = popScale; scaleY = popScale }
                                .clickable {
                                    haptic.performHapticFeedback(if (isLiked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                                    if (!isLiked) likeTrigger++
                                    onLikeClick()
                                },
                        )
                    }
                }
                Box {
                    CanopyIconButton(
                        icon = phosphorIcon("dots-three-vertical"),
                        onClick = { menuExpanded = true },
                        size = 32.dp,
                        iconSize = 18.dp,
                        contentDescription = "Mehr",
                    )
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        AnalyzeMenuItem { menuExpanded = false; analyzer.analyzeTracks(listOf(track)) }
                        DropdownMenuItem(
                            text = { Text("Zu Playlist hinzufügen") },
                            leadingIcon = { Icon(phosphorIcon("plus-circle"), contentDescription = null, tint = Canopy.accent) },
                            onClick = { menuExpanded = false; onAddToPlaylistClick() },
                        )
                        DropdownMenuItem(
                            text = { Text("Zur Warteschlange hinzufügen") },
                            leadingIcon = { Icon(phosphorIcon("list-plus"), contentDescription = null, tint = Canopy.accent) },
                            onClick = { menuExpanded = false; onAddToQueueClick() },
                        )
                        DropdownMenuItem(
                            text = { Text("Herunterladen") },
                            leadingIcon = { Icon(phosphorIcon("download-simple"), contentDescription = null, tint = Canopy.accent) },
                            onClick = { menuExpanded = false; onDownloadClick() },
                        )
                        DropdownMenuItem(
                            text = { Text("Teilen") },
                            leadingIcon = { Icon(phosphorIcon("share-network"), contentDescription = null, tint = Canopy.accent) },
                            onClick = { menuExpanded = false; context.shareText(track.webpageUrl) },
                        )
                    }
                }
            }
        }
    }
}

private fun formatTrackDuration(sec: Int?): String? {
    if (sec == null || sec <= 0) return null
    return "%d:%02d".format(sec / 60, sec % 60)
}
