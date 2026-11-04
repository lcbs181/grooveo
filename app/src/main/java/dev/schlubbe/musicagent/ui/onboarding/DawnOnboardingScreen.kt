package dev.schlubbe.musicagent.ui.onboarding

import androidx.compose.runtime.mutableStateOf
import dev.schlubbe.musicagent.ui.theme.DawnSky
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import dev.schlubbe.musicagent.R
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Canopy
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex

private const val STEP_WELCOME = 0
private const val STEP_NAME = 1
private const val STEP_SPOTIFY = 2
private const val STEP_GENRES = 3
private const val STEP_ARTISTS = 4
private const val STEP_DONE = 5
private const val MIN_ARTISTS = 3

/** Dawn Music's first run, modelled on Spotify's: one question per screen,
 * a big pill button at the bottom, a thin progress bar on top. Welcome, name,
 * genre tiles, then a grid of round artist photos to tap (with search), then a
 * short "all set" moment while Home loads behind it. */
@Composable
fun DawnOnboardingScreen(
    onFinished: () -> Unit,
    onSpotifyImport: () -> Unit,
    viewModel: DawnOnboardingViewModel = hiltViewModel(),
) {
    var step by rememberSaveable { mutableIntStateOf(STEP_WELCOME) }
    BackHandler(enabled = step in STEP_NAME..STEP_ARTISTS) { step-- }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DawnSky),
    ) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn(tween(260)))
                    .togetherWith(slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut(tween(200)))
            },
            label = "onboarding",
        ) { current ->
            when (current) {
                STEP_WELCOME -> WelcomeStep(onNext = { step = STEP_NAME })
                STEP_NAME -> NameStep(viewModel, onBack = { step-- }, onNext = { step = STEP_SPOTIFY })
                STEP_SPOTIFY -> SpotifyStep(
                    onBack = { step-- },
                    // the import follows the Spotify artists itself, so genres and
                    // artists are skipped; the name is saved first
                    onImport = { viewModel.finish(onSpotifyImport) },
                    onSkip = { step = STEP_GENRES },
                )
                STEP_GENRES -> GenreStep(viewModel, onBack = { step-- }, onNext = {
                    viewModel.loadSuggestions()
                    step = STEP_ARTISTS
                })
                STEP_ARTISTS -> ArtistStep(viewModel, onBack = { step-- }, onNext = { step = STEP_DONE })
                else -> DoneStep(viewModel, onFinished)
            }
        }
    }
}

// --- shared pieces ---

@Composable
private fun StepScaffold(
    progress: Int,
    onBack: () -> Unit,
    title: String,
    subtitle: String?,
    button: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 24.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(phosphorIcon("caret-left"), contentDescription = "Zurück", tint = Canopy.text, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.size(8.dp))
            ProgressBar(progress, modifier = Modifier.weight(1f))
        }
        Column(modifier = Modifier.padding(horizontal = 24.dp).padding(top = 20.dp)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, color = Canopy.text, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = Canopy.neutral600, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 16.dp)) { content() }
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
            button()
        }
    }
}

/** Four segments: name, Spotify, genres, artists. */
@Composable
private fun ProgressBar(progress: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(4) { i ->
            val fill by animateFloatAsState(if (i < progress) 1f else 0f, tween(400), label = "progress")
            Box(
                modifier = Modifier.weight(1f).height(5.dp).clip(CircleShape).background(Canopy.neutral300),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fill)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(Brush.horizontalGradient(listOf(Canopy.accent, Canopy.accent2))),
                )
            }
        }
    }
}

@Composable
private fun PillButton(text: String, enabled: Boolean = true, secondary: Boolean = false, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val background = when {
        !enabled -> Brush.horizontalGradient(listOf(Canopy.neutral300, Canopy.neutral300))
        secondary -> Brush.horizontalGradient(listOf(Color.White, Color.White))
        else -> Brush.horizontalGradient(listOf(Canopy.accent, Canopy.accent2))
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(CircleShape)
            .background(background)
            .then(if (secondary) Modifier.border(1.dp, Canopy.divider, CircleShape) else Modifier)
            .clickable(enabled = enabled) {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = when {
                !enabled -> Canopy.neutral500
                secondary -> Canopy.text
                else -> Color.White
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

// --- steps ---

/** Watercolour beach at sunrise (Pixabay) with a watercolour dolphin leaping in
 * front; the copy sits on a dark fade at the bottom so white text stays readable
 * over the painting. */
@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val rise by animateFloatAsState(if (shown) 0f else 60f, tween(900), label = "rise")
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1B2D4A))) {
        Image(
            painterResource(R.drawable.dawn_sea_watercolor),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color.Transparent,
                        1f to Color(0xE60B1A2E),
                    ),
                ),
        )
        Image(
            painterResource(R.drawable.dawn_dolphin),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 120.dp)
                .height(320.dp)
                .graphicsLayer { translationY = rise.dp.toPx(); alpha = 1f - rise / 60f },
        )
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))) {
                    Image(painterResource(R.drawable.ic_dawn_background), contentDescription = null, modifier = Modifier.size(36.dp))
                    Image(painterResource(R.drawable.ic_dawn_foreground), contentDescription = null, modifier = Modifier.size(36.dp))
                }
                Text(stringResource(R.string.app_name), color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Millionen Songs.\nAlles kostenlos.",
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 34.sp, lineHeight = 40.sp),
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                "Ohne Konto, ohne Werbung. Einfach anfangen.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                modifier = Modifier.padding(top = 10.dp, bottom = 28.dp),
            )
            PillButton("Los geht's", onClick = onNext)
        }
    }
}

@Composable
private fun NameStep(viewModel: DawnOnboardingViewModel, onBack: () -> Unit, onNext: () -> Unit) {
    val name by viewModel.name.collectAsState()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    val next = {
        keyboard?.hide()
        onNext()
    }
    StepScaffold(
        progress = 1,
        onBack = onBack,
        title = "Wie heißt du?",
        subtitle = "So begrüßen wir dich auf der Startseite.",
        button = { PillButton(if (name.isBlank()) "Überspringen" else "Weiter", secondary = name.isBlank(), onClick = next) },
    ) {
        BasicTextField(
            value = name,
            onValueChange = { viewModel.name.value = it.take(30) },
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineMedium.copy(color = Canopy.text, fontWeight = FontWeight.Bold),
            cursorBrush = SolidColor(Canopy.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { next() }),
            modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth().focusRequester(focus),
            decorationBox = { field ->
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.White)
                            .border(1.5.dp, if (name.isBlank()) Canopy.divider else Canopy.accent, RoundedCornerShape(18.dp))
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                    ) {
                        if (name.isEmpty()) {
                            Text("Dein Name", style = MaterialTheme.typography.headlineMedium, color = Canopy.neutral400, fontWeight = FontWeight.Bold)
                        }
                        field()
                    }
                }
            },
        )
    }
}

/** Spotify's "Browse all" tiles: a colour block per genre, picked ones get a check. */
// all at least 4.7:1 against the white labels
private val TILE_COLORS = listOf(
    Color(0xFFC2401F), Color(0xFFC93A78), Color(0xFF7B4AA8), Color(0xFF8A5A00),
    Color(0xFF2F7F73), Color(0xFF1665C8), Color(0xFFB83A3A), Color(0xFF9C2F5A),
    Color(0xFF1F7A8C), Color(0xFFA0522D),
)

@Composable
private fun GenreStep(viewModel: DawnOnboardingViewModel, onBack: () -> Unit, onNext: () -> Unit) {
    val picked by viewModel.preferredGenres.collectAsState()
    val covers by viewModel.genreCovers.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadGenreCovers() }
    val haptic = LocalHapticFeedback.current
    StepScaffold(
        progress = 3,
        onBack = onBack,
        title = "Was hörst du gern?",
        subtitle = "Wähle so viele Genres, wie du willst.",
        button = { PillButton(if (picked.isEmpty()) "Überspringen" else "Weiter (${picked.size})", secondary = picked.isEmpty(), onClick = onNext) },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(ONBOARDING_GENRES.size) { i ->
                val genre = ONBOARDING_GENRES[i]
                val selected = genre in picked
                val scale by animateFloatAsState(if (selected) 0.96f else 1f, label = "tile")
                Box(
                    modifier = Modifier
                        .aspectRatio(1.7f)
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .clip(RoundedCornerShape(16.dp))
                        .background(TILE_COLORS[i % TILE_COLORS.size])
                        .then(if (selected) Modifier.border(3.dp, Color.White, RoundedCornerShape(16.dp)) else Modifier)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            viewModel.toggleGenre(genre)
                        }
                        .padding(14.dp),
                ) {
                    Text(
                        genre,
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopStart).padding(end = 30.dp).zIndex(1f),
                    )
                    // Spotify's tilted artwork corner: a photo of the genre's
                    // best-known artist, a translucent card until it has loaded
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(62.dp)
                            .graphicsLayer { rotationZ = 20f; translationX = 22.dp.toPx(); translationY = 16.dp.toPx() }
                            .shadow(8.dp, RoundedCornerShape(10.dp))
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.22f)),
                    ) {
                        covers[genre]?.let { url ->
                            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                    AnimatedVisibility(
                        visible = selected,
                        enter = scaleIn() + fadeIn(),
                        exit = scaleOut() + fadeOut(),
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) {
                        CheckBadge(size = 28)
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckBadge(size: Int) {
    Box(
        modifier = Modifier.size(size.dp).clip(CircleShape).background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Icon(phosphorIcon("check", filled = true), contentDescription = null, tint = Canopy.accent, modifier = Modifier.size((size * 0.6f).dp))
    }
}

@Composable
private fun ArtistStep(viewModel: DawnOnboardingViewModel, onBack: () -> Unit, onNext: () -> Unit) {
    val suggestions by viewModel.suggestions.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val query by viewModel.query.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val haptic = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val selectedKeys = selected.map { it.key }.toSet()
    // picked artists stay visible on top even while a search shows other ones
    val grid = (searchResults ?: (selected + suggestions.filter { it.key !in selectedKeys }))
    val missing = (MIN_ARTISTS - selected.size).coerceAtLeast(0)

    StepScaffold(
        progress = 4,
        onBack = onBack,
        title = "Wähle 3 oder mehr Künstler, die du magst.",
        subtitle = null,
        button = {
            PillButton(
                text = if (missing > 0) "Noch $missing auswählen" else "Fertig",
                enabled = missing == 0,
                onClick = onNext,
            )
        },
    ) {
        Column {
            Row(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .border(1.dp, Canopy.divider, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(phosphorIcon("magnifying-glass"), contentDescription = null, tint = Canopy.neutral600, modifier = Modifier.size(20.dp))
                BasicTextField(
                    value = query,
                    onValueChange = viewModel::onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Canopy.text),
                    cursorBrush = SolidColor(Canopy.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.weight(1f),
                    decorationBox = { field ->
                        if (query.isEmpty()) Text("Künstler suchen", style = MaterialTheme.typography.bodyLarge, color = Canopy.neutral500)
                        field()
                    },
                )
                if (query.isNotEmpty()) {
                    Icon(
                        phosphorIcon("x"),
                        contentDescription = "Suche leeren",
                        tint = Canopy.neutral600,
                        modifier = Modifier.size(20.dp).clip(CircleShape).clickable { viewModel.onQueryChange("") },
                    )
                }
            }
            if (searchResults?.isEmpty() == true) {
                Text(
                    "Niemanden gefunden. Versuch einen anderen Namen.",
                    color = Canopy.neutral600,
                    modifier = Modifier.padding(24.dp),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (grid.isEmpty() && searchResults == null) {
                    items(12) { ArtistPlaceholder() }
                }
                items(grid, key = { it.key }) { pick ->
                    ArtistTile(pick, selected = pick.key in selectedKeys) {
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        viewModel.toggleArtist(pick)
                    }
                }
                item(span = { GridItemSpan(3) }) { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun ArtistTile(pick: ArtistPick, selected: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (selected) 0.92f else 1f, label = "artist")
    Column(
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = pick.artist != null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .fillMaxSize(scale)
                    .clip(CircleShape)
                    .background(Canopy.neutral200)
                    .then(if (selected) Modifier.border(3.dp, Canopy.accent, CircleShape) else Modifier),
            ) {
                AsyncImage(
                    model = pick.artist?.thumbnailUrl,
                    contentDescription = pick.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (selected) Box(Modifier.fillMaxSize().background(Canopy.accent.copy(alpha = 0.18f)))
            }
            val badge by animateFloatAsState(if (selected) 1f else 0f, label = "badge")
            if (badge > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .graphicsLayer { scaleX = badge; scaleY = badge; alpha = badge }
                        .size(30.dp).clip(CircleShape).background(Canopy.accent).border(2.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(phosphorIcon("check", filled = true), contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
        Text(
            pick.artist?.name ?: pick.name,
            style = MaterialTheme.typography.labelLarge,
            color = Canopy.text,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ArtistPlaceholder() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape).background(Canopy.neutral200))
        Box(Modifier.padding(top = 10.dp).size(width = 64.dp, height = 10.dp).clip(CircleShape).background(Canopy.neutral200))
    }
}

/** Offers to bring the Spotify library along before starting from scratch. */
@Composable
private fun SpotifyStep(onBack: () -> Unit, onImport: () -> Unit, onSkip: () -> Unit) {
    StepScaffold(
        progress = 2,
        onBack = onBack,
        title = "Kommst du von Spotify oder Amazon Music?",
        subtitle = "Nimm deine Musik einfach mit, dann ist von Anfang an alles da.",
        button = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Meine Musik übernehmen", onClick = onImport)
                PillButton("Nein, ich fange neu an", secondary = true, onClick = onSkip)
            }
        },
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White.copy(alpha = 0.8f))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            listOf(
                "heart" to "Deine Lieblingssongs",
                "queue" to "Alle deine Playlists",
                "microphone-stage" to "Deine Lieblingskünstler",
            ).forEach { (icon, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        modifier = Modifier.size(40.dp).clip(CircleShape).background(Canopy.accent100),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(phosphorIcon(icon), contentDescription = null, tint = Canopy.accent700, modifier = Modifier.size(20.dp))
                    }
                    Text(label, style = MaterialTheme.typography.titleMedium, color = Canopy.text)
                }
            }
            Text(
                "Bei Spotify meldest du dich einmal an und tippst auf „Alles exportieren“, Amazon Music kommt als Datei über TuneMyMusic – den Rest macht die App.",
                style = MaterialTheme.typography.bodyMedium,
                color = Canopy.neutral600,
            )
        }
    }
}

@Composable
private fun DoneStep(viewModel: DawnOnboardingViewModel, onFinished: () -> Unit) {
    val name by viewModel.name.collectAsState()
    var risen by rememberSaveable { mutableIntStateOf(0) }
    val sun by animateFloatAsState(if (risen == 1) 0f else 80f, tween(1100), label = "sun")
    LaunchedEffect(Unit) {
        risen = 1
        kotlinx.coroutines.delay(1700)
        viewModel.finish(onFinished)
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painterResource(R.drawable.dawn_dolphin),
            contentDescription = null,
            modifier = Modifier
                .padding(top = sun.dp)
                .height(220.dp)
                .graphicsLayer { alpha = 1f - sun / 80f },
        )
        Text(
            if (name.isBlank()) "Alles bereit!" else "Alles bereit, ${name.trim()}!",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = Canopy.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 28.dp),
        )
        Text(
            "Wir stellen deine Startseite zusammen …",
            style = MaterialTheme.typography.bodyLarge,
            color = Canopy.neutral600,
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
