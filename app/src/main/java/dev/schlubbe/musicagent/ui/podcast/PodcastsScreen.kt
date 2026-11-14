package dev.schlubbe.musicagent.ui.podcast

import androidx.compose.foundation.background
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Atom
import com.adamglin.phosphoricons.regular.Baby
import com.adamglin.phosphoricons.regular.Bank
import com.adamglin.phosphoricons.regular.Brain
import com.adamglin.phosphoricons.regular.Compass
import com.adamglin.phosphoricons.regular.Crown
import com.adamglin.phosphoricons.regular.Detective
import com.adamglin.phosphoricons.regular.Feather
import com.adamglin.phosphoricons.regular.Ghost
import com.adamglin.phosphoricons.regular.Knife
import com.adamglin.phosphoricons.regular.Lightbulb
import com.adamglin.phosphoricons.regular.Newspaper
import com.adamglin.phosphoricons.regular.Rocket
import com.adamglin.phosphoricons.regular.Scroll
import com.adamglin.phosphoricons.regular.Smiley
import com.adamglin.phosphoricons.regular.Sword
import dev.schlubbe.musicagent.podcast.ForYouItem
import dev.schlubbe.musicagent.podcast.PodcastEpisode
import dev.schlubbe.musicagent.ui.components.skeleton
import dev.schlubbe.musicagent.ui.theme.CanopyShapes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.schlubbe.musicagent.data.local.entity.PodcastProgressEntity
import dev.schlubbe.musicagent.podcast.PodcastShow
import dev.schlubbe.musicagent.podcast.ShowKind
import dev.schlubbe.musicagent.ui.components.CanopyChip
import dev.schlubbe.musicagent.ui.components.CanopyIconButton
import dev.schlubbe.musicagent.ui.components.TrackThumbnail
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.theme.CanopyPillShape
import dev.schlubbe.musicagent.ui.theme.LocalChromeInset
import dev.schlubbe.musicagent.ui.theme.dawnContainer

/** "Entdecken": the library of Podcasts, Hörspiele and Hörbücher. A carousel of tips,
 * genre tiles, shelves per category and the person's own Abos; typing searches. */
@Composable
fun PodcastsScreen(
    onNavigateBack: () -> Unit,
    onShowSelected: (showId: String) -> Unit,
    onEpisodePlayed: () -> Unit,
    onOpenAdvent: () -> Unit = {},
    viewModel: PodcastsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()
    val inProgress by viewModel.inProgress.collectAsState()
    var showAddFeed by remember { mutableStateOf(false) }

    LaunchedEffect(state.openShowId) {
        state.openShowId?.let {
            showAddFeed = false
            viewModel.onShowOpened()
            onShowSelected(it)
        }
    }

    val kind = state.kind
    val shelves = state.shelves.filter { kind == null || it.kind == kind }
    val playEpisode: (PodcastEpisode) -> Unit = { viewModel.play(it); onEpisodePlayed() }
    // the carousel: personal tips first, else the top of each shelf
    val forYou = state.forYou.filter {
        when (it) {
            is ForYouItem.Show -> kind == null || it.show.kind == kind
            is ForYouItem.Episode -> kind == null // episodes carry no kind
        }
    }
    val hero: List<HeroItem> = if (forYou.isNotEmpty()) {
        forYou.take(HERO_SIZE).map { it.toHero(onShowSelected, playEpisode) }
    } else {
        val tops = shelves.map { shelf -> shelf.shows.filter { it.imageUrl != null }.take(2).map { it to shelf.title } }
        (0 until 2).flatMap { i -> tops.mapNotNull { it.getOrNull(i) } }.distinctBy { it.first.id }.take(HERO_SIZE)
            .map { (show, label) -> HeroItem(show.title, label, show.author, show.imageUrl, playable = false) { onShowSelected(show.id) } }
    }
    val heroIds = forYou.take(HERO_SIZE).map { it.key() }.toSet()

    Scaffold(containerColor = dawnContainer, contentWindowInsets = WindowInsets(0)) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalChromeInset.current + 24.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CanopyIconButton(icon = phosphorIcon("caret-left"), onClick = onNavigateBack, iconSize = 20.dp, contentDescription = "Zurück")
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text("Entdecken", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Canopy.text)
                        Text("Podcasts, Hörspiele & Hörbücher", style = MaterialTheme.typography.bodySmall, color = Canopy.neutral500)
                    }
                    CanopyIconButton(
                        icon = phosphorIcon("rss"),
                        onClick = { showAddFeed = true },
                        iconSize = 20.dp,
                        contentDescription = "RSS-Feed hinzufügen",
                    )
                }
            }
            item { SearchRow(state.query, kind, viewModel::setQuery) { viewModel.search() } }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { CanopyChip("Alle", active = kind == null, onClick = { viewModel.setKind(null) }) }
                    item { CanopyChip("Podcasts", active = kind == ShowKind.PODCAST, onClick = { viewModel.setKind(ShowKind.PODCAST) }) }
                    item { CanopyChip("Hörspiele", active = kind == ShowKind.HOERSPIEL, onClick = { viewModel.setKind(ShowKind.HOERSPIEL) }) }
                    item { CanopyChip("Hörbücher", active = kind == ShowKind.HOERBUCH, onClick = { viewModel.setKind(ShowKind.HOERBUCH) }) }
                }
            }

            if (state.query.trim().length >= 2) {
                when {
                    state.searching && state.results.isEmpty() -> item { Loading() }
                    state.error != null -> item { Hint(state.error!!) }
                    state.results.isEmpty() && !state.searching -> item { Hint("Nichts gefunden") }
                    kind != null -> items(state.results, key = { "r:${it.id}" }) { show -> ShowRow(show) { onShowSelected(show.id) } }
                    else -> ShowKind.entries.forEach { k ->
                        val group = state.results.filter { it.kind == k }
                        if (group.isNotEmpty()) {
                            item(key = "rh:${k.key}") { Section(kindLabel(k)) }
                            items(group, key = { "r:${it.id}" }) { show -> ShowRow(show) { onShowSelected(show.id) } }
                        }
                    }
                }
                return@LazyColumn
            }

            if (dev.schlubbe.musicagent.ui.advent.adventSeason()) {
                item(key = "advent") {
                    dev.schlubbe.musicagent.ui.advent.AdventBanner(onOpenAdvent, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp))
                }
            }
            item(key = "hero") {
                if (hero.isEmpty()) HeroSkeleton() else HeroCarousel(hero)
            }
            if (inProgress.isNotEmpty()) {
                item { ShelfHeader("Weiterhören", "Da, wo du aufgehört hast") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(inProgress, key = { it.audioUrl }) { progress ->
                            var menu by remember { mutableStateOf(false) }
                            Column(
                                modifier = Modifier.width(CARD).combinedClickable(onLongClick = { menu = true }) {
                                    viewModel.resume(progress)
                                    onEpisodePlayed()
                                },
                            ) {
                                ResumeMenu(menu, onDismiss = { menu = false }, onMarkHeard = { viewModel.markHeard(progress) }, onForget = { viewModel.forget(progress) })
                                Box {
                                    Cover(progress.imageUrl, progress.title, CARD)
                                    PlayBadge(Modifier.align(Alignment.BottomEnd))
                                }
                                if (progress.durationMs > 0) {
                                    LinearProgressIndicator(
                                        progress = { (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f) },
                                        color = Canopy.accent,
                                        trackColor = Canopy.divider,
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(3.dp).clip(CanopyPillShape),
                                    )
                                }
                                CardText(progress.title, remainingText(progress.positionMs, progress.durationMs) ?: progress.showTitle)
                            }
                        }
                    }
                }
            }
            val moreForYou = forYou.filter { it.key() !in heroIds }
            if (moreForYou.isNotEmpty()) {
                item { ShelfHeader("Mehr für dich", "Nach dem, was du hörst und abonniert hast") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(moreForYou, key = { it.key() }) { item ->
                            when (item) {
                                is ForYouItem.Episode -> Column(Modifier.width(CARD).clickable { playEpisode(item.episode) }) {
                                    Box {
                                        Cover(item.episode.imageUrl, item.episode.title, CARD)
                                        PlayBadge(Modifier.align(Alignment.BottomEnd))
                                    }
                                    CardText(item.episode.title, item.reason)
                                }
                                is ForYouItem.Show -> ShowCard(item.show, item.reason) { onShowSelected(item.show.id) }
                            }
                        }
                    }
                }
            }

            item { ShelfHeader("Genres", "Tippe, um zu stöbern") }
            item { GenreGrid(genresFor(kind)) { genre -> viewModel.browseGenre(genre.query, genre.kind) } }

            shelves.forEach { shelf ->
                if (shelf.loaded && shelf.shows.isEmpty()) return@forEach // source not reachable
                item(key = "sh:${shelf.key}") {
                    Column {
                        ShelfHeader(shelf.title, shelf.subtitle, badge = if (kind == null) kindLabel(shelf.kind) else null)
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (!shelf.loaded) {
                                items(5) { SkeletonCard() }
                            } else {
                                items(shelf.shows, key = { it.id }) { show -> ShowCard(show, show.author) { onShowSelected(show.id) } }
                            }
                        }
                    }
                }
            }

            val subscribed = subscriptions.filter { kind == null || ShowKind.of(it.kind) == kind }
            if (subscribed.isNotEmpty()) {
                item { ShelfHeader("Deine Abos", if (subscribed.size == 1) "1 Reihe" else "${subscribed.size} Reihen") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(subscribed, key = { "s:${it.id}" }) { show ->
                            Column(modifier = Modifier.width(CARD).clickable { onShowSelected(show.id) }) {
                                Cover(show.imageUrl, show.title, CARD)
                                CardText(show.title, null)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddFeed) {
        AddFeedDialog(
            busy = state.addingFeed,
            error = state.feedError,
            onAdd = viewModel::addFeed,
            onDismiss = {
                showAddFeed = false
                viewModel.clearFeedError()
            },
        )
    }
}

private fun kindLabel(kind: ShowKind) = when (kind) {
    ShowKind.PODCAST -> "Podcasts"
    ShowKind.HOERSPIEL -> "Hörspiele"
    ShowKind.HOERBUCH -> "Hörbücher"
}

private fun ForYouItem.key() = when (this) {
    is ForYouItem.Episode -> "e:" + episode.audioUrl
    is ForYouItem.Show -> "s:" + show.id
}

// ---- carousel ----

private class HeroItem(
    val title: String,
    val label: String,
    val subtitle: String?,
    val imageUrl: String?,
    val playable: Boolean,
    val onClick: () -> Unit,
)

private fun ForYouItem.toHero(onShow: (String) -> Unit, onPlay: (PodcastEpisode) -> Unit) = when (this) {
    is ForYouItem.Episode -> HeroItem(episode.title, reason, episode.showTitle, episode.imageUrl, playable = true) { onPlay(episode) }
    is ForYouItem.Show -> HeroItem(show.title, reason, show.author, show.imageUrl, playable = false) { onShow(show.id) }
}

@Composable
private fun HeroCarousel(items: List<HeroItem>) {
    val pager = rememberPagerState { items.size }
    Column {
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = 16.dp),
            pageSpacing = 12.dp,
            modifier = Modifier.padding(top = 4.dp),
        ) { page ->
            val item = items[page]
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HERO_HEIGHT)
                    .clip(CanopyShapes.large)
                    .background(Canopy.accent700)
                    .clickable(onClick = item.onClick),
            ) {
                // the cover twice: blurred and cropped as the backdrop, sharp on the right
                AsyncImage(
                    model = item.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().blur(24.dp).graphicsLayer { alpha = 0.85f },
                )
                Box(
                    Modifier.matchParentSize().background(
                        Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Black.copy(alpha = 0.25f))),
                    ),
                )
                Row(
                    modifier = Modifier.matchParentSize().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            item.label.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            item.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        item.subtitle?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .clip(CanopyPillShape)
                                .background(Color.White)
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                phosphorIcon(if (item.playable) "play" else "headphones", filled = item.playable),
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(if (item.playable) "Abspielen" else "Ansehen", style = MaterialTheme.typography.labelLarge, color = Color.Black)
                        }
                    }
                    Box(modifier = Modifier.shadow(12.dp, CanopyShapes.medium).clip(CanopyShapes.medium)) {
                        TrackThumbnail(item.imageUrl, size = 128.dp, seed = item.title, square = true)
                    }
                }
            }
        }
        if (items.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(items.size) { i ->
                    val selected = pager.currentPage == i
                    val width by animateDpAsState(if (selected) 18.dp else 6.dp, label = "dot")
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .height(6.dp)
                            .width(width)
                            .clip(CanopyPillShape)
                            .background(if (selected) Canopy.accent else Canopy.neutral300),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroSkeleton() {
    Box(
        Modifier
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 16.dp)
            .fillMaxWidth()
            .height(HERO_HEIGHT)
            .clip(CanopyShapes.large)
            .skeleton(),
    )
}

// ---- genres ----

private class Genre(val label: String, val query: String, val kind: ShowKind, val color: Color, val icon: ImageVector)

private val HOERSPIEL_GENRES = listOf(
    Genre("Krimi", "Krimi", ShowKind.HOERSPIEL, Color(0xFF263447), PhosphorIcons.Regular.Detective),
    Genre("Fantasy", "Fantasy", ShowKind.HOERSPIEL, Color(0xFF5A4FB0), PhosphorIcons.Regular.Sword),
    Genre("Science-Fiction", "Science Fiction", ShowKind.HOERSPIEL, Color(0xFF1F5F8C), PhosphorIcons.Regular.Rocket),
    Genre("Grusel", "Grusel", ShowKind.HOERSPIEL, Color(0xFF3F3A4F), PhosphorIcons.Regular.Ghost),
    Genre("Märchen", "Märchen", ShowKind.HOERSPIEL, Color(0xFF2E6E4E), PhosphorIcons.Regular.Crown),
    Genre("Kinder", "Kinder", ShowKind.HOERSPIEL, Color(0xFFC2410C), PhosphorIcons.Regular.Baby),
)

private val HOERBUCH_GENRES = listOf(
    Genre("Klassiker", "Klassiker", ShowKind.HOERBUCH, Color(0xFF8A5A00), PhosphorIcons.Regular.Scroll),
    Genre("Krimi", "Krimi", ShowKind.HOERBUCH, Color(0xFF263447), PhosphorIcons.Regular.Detective),
    Genre("Märchen", "Märchen", ShowKind.HOERBUCH, Color(0xFF2E6E4E), PhosphorIcons.Regular.Crown),
    Genre("Gedichte", "Gedichte", ShowKind.HOERBUCH, Color(0xFF9D3D5C), PhosphorIcons.Regular.Feather),
    Genre("Abenteuer", "Abenteuer", ShowKind.HOERBUCH, Color(0xFF1F6F5F), PhosphorIcons.Regular.Compass),
    Genre("Philosophie", "Philosophie", ShowKind.HOERBUCH, Color(0xFF4B4F7A), PhosphorIcons.Regular.Brain),
)

private val PODCAST_GENRES = listOf(
    Genre("Wissen", "Wissen", ShowKind.PODCAST, Color(0xFF1F6F8B), PhosphorIcons.Regular.Lightbulb),
    Genre("True Crime", "True Crime", ShowKind.PODCAST, Color(0xFF7A1F2B), PhosphorIcons.Regular.Knife),
    Genre("Comedy", "Comedy", ShowKind.PODCAST, Color(0xFFB45309), PhosphorIcons.Regular.Smiley),
    Genre("Nachrichten", "Nachrichten", ShowKind.PODCAST, Color(0xFF334155), PhosphorIcons.Regular.Newspaper),
    Genre("Geschichte", "Geschichte", ShowKind.PODCAST, Color(0xFF6B4F2A), PhosphorIcons.Regular.Bank),
    Genre("Wissenschaft", "Wissenschaft", ShowKind.PODCAST, Color(0xFF2D6A4F), PhosphorIcons.Regular.Atom),
)

private fun genresFor(kind: ShowKind?): List<Genre> = when (kind) {
    ShowKind.HOERSPIEL -> HOERSPIEL_GENRES
    ShowKind.HOERBUCH -> HOERBUCH_GENRES
    ShowKind.PODCAST -> PODCAST_GENRES
    null -> listOf(HOERSPIEL_GENRES[0], HOERSPIEL_GENRES[1], PODCAST_GENRES[0], PODCAST_GENRES[1], HOERBUCH_GENRES[0], HOERSPIEL_GENRES[5])
}

@Composable
private fun GenreGrid(genres: List<Genre>, onClick: (Genre) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        genres.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { genre ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(72.dp)
                            .clip(CanopyShapes.small)
                            .background(Brush.linearGradient(listOf(genre.color, genre.color.copy(alpha = 0.75f).compositeOver(Color.Black))))
                            .clickable { onClick(genre) }
                            .padding(12.dp),
                    ) {
                        Text(genre.label, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Icon(
                            genre.icon,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.35f),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(46.dp)
                                .graphicsLayer { rotationZ = -14f; translationX = 8.dp.toPx(); translationY = 10.dp.toPx() },
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ---- shelves ----

@Composable
private fun ShelfHeader(title: String, subtitle: String, badge: String? = null) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 12.dp)) {
        if (badge != null) {
            Text(badge.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Canopy.accent)
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Canopy.text)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Canopy.neutral500, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun Cover(url: String?, seed: String, size: Dp) {
    Box(modifier = Modifier.shadow(6.dp, CanopyShapes.medium).clip(CanopyShapes.medium)) {
        TrackThumbnail(url, size = size, seed = seed, square = true)
    }
}

@Composable
private fun ShowCard(show: PodcastShow, subtitle: String?, onClick: () -> Unit) {
    Column(modifier = Modifier.width(CARD).clickable(onClick = onClick)) {
        Cover(show.imageUrl, show.title, CARD)
        CardText(show.title, subtitle)
    }
}

@Composable
private fun SkeletonCard() {
    Column(modifier = Modifier.width(CARD)) {
        Box(Modifier.size(CARD).clip(CanopyShapes.medium).skeleton())
        Box(Modifier.padding(top = 10.dp).fillMaxWidth(0.85f).height(12.dp).clip(CanopyPillShape).skeleton())
        Box(Modifier.padding(top = 6.dp).fillMaxWidth(0.5f).height(10.dp).clip(CanopyPillShape).skeleton())
    }
}

@Composable
private fun CardText(title: String, subtitle: String?) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = Canopy.text,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 8.dp),
    )
    if (!subtitle.isNullOrBlank()) {
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Canopy.neutral500, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PlayBadge(modifier: Modifier) {
    Icon(
        phosphorIcon("play-circle", filled = true),
        contentDescription = null,
        tint = Canopy.accent,
        modifier = modifier.padding(8.dp).size(34.dp).clip(CanopyPillShape).background(Canopy.surface),
    )
}

private val CARD = 146.dp
private val HERO_HEIGHT = 196.dp
private const val HERO_SIZE = 5

@Composable
private fun SearchRow(query: String, kind: ShowKind?, onChange: (String) -> Unit, onSubmit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp)
            .clip(CanopyPillShape)
            .background(Canopy.surface)
            .border(1.dp, Canopy.divider, CanopyPillShape)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(phosphorIcon("magnifying-glass"), contentDescription = null, tint = Canopy.neutral500, modifier = Modifier.size(18.dp))
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Canopy.text),
                cursorBrush = SolidColor(Canopy.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (query.isEmpty()) {
                Text(
                    when (kind) {
                        ShowKind.PODCAST -> "Podcasts suchen"
                        ShowKind.HOERSPIEL -> "Hörspiele suchen"
                        ShowKind.HOERBUCH -> "Hörbücher, Autoren suchen"
                        null -> "Podcasts, Hörspiele, Hörbücher suchen"
                    },
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
                modifier = Modifier.size(18.dp).clickable { onChange("") },
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun Loading() {
    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Canopy.accent)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Canopy.neutral500, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

@Composable
internal fun ShowRow(show: PodcastShow, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackThumbnail(show.imageUrl, size = 56.dp, seed = show.title)
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(show.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val subtitle = listOfNotNull(show.author, show.episodeCount?.takeIf { it > 0 }?.let { if (it == 1) "1 Folge" else "$it Folgen" }).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Canopy.neutral500, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ResumeRow(progress: PodcastProgressEntity, onMarkHeard: () -> Unit, onForget: () -> Unit, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackThumbnail(progress.imageUrl, size = 56.dp, seed = progress.title)
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(progress.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(progress.showTitle, remainingText(progress.positionMs, progress.durationMs)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Canopy.neutral500,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (progress.durationMs > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f) },
                    color = Canopy.accent,
                    trackColor = Canopy.divider,
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(CanopyPillShape),
                )
            }
        }
        Icon(phosphorIcon("play", filled = true), contentDescription = "Weiterhören", tint = Canopy.accent, modifier = Modifier.padding(start = 12.dp).size(22.dp))
        Box {
            CanopyIconButton(icon = phosphorIcon("dots-three"), onClick = { menu = true }, contentDescription = "Weitere Optionen")
            ResumeMenu(menu, onDismiss = { menu = false }, onMarkHeard = onMarkHeard, onForget = onForget)
        }
    }
}

/** "Weiterhören" entries the person does not want to see: counted as heard (the next
 * part moves up), or forgotten together with the position. */
@Composable
internal fun ResumeMenu(expanded: Boolean, onDismiss: () -> Unit, onMarkHeard: () -> Unit, onForget: () -> Unit) {
    androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("Als gehört markieren") },
            leadingIcon = { Icon(phosphorIcon("check-circle"), contentDescription = null, tint = Canopy.accent) },
            onClick = { onDismiss(); onMarkHeard() },
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("Aus Weiterhören entfernen") },
            leadingIcon = { Icon(phosphorIcon("trash"), contentDescription = null, tint = Canopy.accent) },
            onClick = { onDismiss(); onForget() },
        )
    }
}

internal fun remainingText(positionMs: Long, durationMs: Long): String? {
    if (durationMs <= 0) return null
    val minutes = ((durationMs - positionMs).coerceAtLeast(0) / 60_000).toInt()
    return if (minutes < 1) "fast fertig" else "noch $minutes Min."
}

@Composable
private fun AddFeedDialog(busy: Boolean, error: String?, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("RSS-Feed hinzufügen") },
        text = {
            Column {
                Text(
                    "Die Feed-Adresse eines Podcasts, z. B. von der Website des Podcasts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Canopy.neutral500,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    placeholder = { Text("https://…/feed.xml") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (url.isNotBlank() && !busy) onAdd(url) }),
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                if (busy) LinearProgressIndicator(color = Canopy.accent, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(url) }, enabled = url.isNotBlank() && !busy) { Text("Hinzufügen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
