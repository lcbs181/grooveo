package dev.schlubbe.musicagent.ui.advent

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import dev.schlubbe.musicagent.R
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.fill.Snowflake
import com.adamglin.phosphoricons.fill.Star
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.CanopyPillShape
import dev.schlubbe.musicagent.ui.theme.LocalChromeInset
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random

// fir green and gold, taken from the Canva header, whatever theme the app is in
private val Night = Color(0xFF05180D)
private val NightMid = Color(0xFF0F2A1A)
private val Dusk = Color(0xFF1D3926)
private val Gold = Color(0xFFE2B451)
private val Cream = Color(0xFFF3EAD7)

private class DoorStyle(val face: Color, val ink: Color)

private val DOOR_STYLES = listOf(
    DoorStyle(Color(0xFF9B1C2C), Cream),
    DoorStyle(Color(0xFF1E4D3A), Cream),
    DoorStyle(Color(0xFF223463), Cream),
    DoorStyle(Color(0xFFB8862B), Color(0xFF2B1D06)),
    DoorStyle(Cream, Color(0xFF7A1622)),
)

// rows of (day, width in quarters), shuffled like a real calendar; 24 is the big one
private val LAYOUT = listOf(
    listOf(13 to 1, 2 to 1, 21 to 2),
    listOf(8 to 2, 17 to 1, 5 to 1),
    listOf(1 to 1, 24 to 2, 11 to 1),
    listOf(19 to 2, 6 to 1, 15 to 1),
    listOf(4 to 2, 22 to 1, 9 to 1),
    listOf(16 to 1, 12 to 1, 20 to 2),
    listOf(3 to 1, 18 to 1, 7 to 1, 23 to 1),
    listOf(10 to 2, 14 to 2),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdventScreen(
    onNavigateBack: () -> Unit,
    onShowSelected: (String) -> Unit,
    onPlaylistSelected: (source: String, sourceId: String) -> Unit,
    onPlaying: () -> Unit,
    viewModel: AdventViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.target) {
        when (val t = state.target) {
            AdventTarget.Player -> onPlaying()
            is AdventTarget.Show -> onShowSelected(t.showId)
            is AdventTarget.Playlist -> onPlaylistSelected(t.source, t.sourceId)
            null -> return@LaunchedEffect
        }
        viewModel.onTargetHandled()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Dusk, NightMid, Night))),
    ) {
        Snowfall(Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = LocalChromeInset.current + 16.dp),
        ) {
            Row(modifier = Modifier.padding(start = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    phosphorIcon("caret-left"),
                    contentDescription = "Zurück",
                    tint = Cream,
                    modifier = Modifier.clip(CanopyPillShape).clickable(onClick = onNavigateBack).padding(10.dp).size(20.dp),
                )
            }
            Header(state)
            Column(
                modifier = Modifier.padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LAYOUT.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(if (row.any { it.first == 24 }) 132.dp else 96.dp)) {
                        row.forEach { (day, span) ->
                            val door = ADVENT_DOORS.first { it.day == day }
                            Door(
                                door = door,
                                style = DOOR_STYLES[(day * 7) % DOOR_STYLES.size],
                                open = day in state.opened,
                                today = !isFinished(state) && state.today.monthValue == 12 && state.today.dayOfMonth == day,
                                cover = state.covers[day],
                                wide = span > 1,
                                modifier = Modifier.weight(span.toFloat()).fillMaxSize(),
                                onClick = {
                                    if (state.canOpen(day)) {
                                        viewModel.onDoorTapped(day)
                                    } else {
                                        val days = state.daysUntil(day)
                                        Toast.makeText(context, if (days == 1L) "Noch 1 Tag bis Türchen $day" else "Noch $days Tage bis Türchen $day", Toast.LENGTH_SHORT).show()
                                    }
                                },
                            )
                        }
                    }
                }
            }
            Text(
                "Hörspiele, Hörbücher und Musik aus der ARD Audiothek und von YouTube Music.",
                style = MaterialTheme.typography.bodySmall,
                color = Cream.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 20.dp),
            )
        }
    }

    state.selected?.let { day ->
        val door = ADVENT_DOORS.first { it.day == day }
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = viewModel::onCardDismissed, sheetState = sheet, containerColor = NightMid) {
            DoorCard(door, state.covers[day], busy = state.busy == day, error = state.error, onMore = { viewModel.openMore(day) }) { viewModel.listen(day) }
        }
    }
}

private fun isFinished(state: AdventUiState) = state.today.monthValue == 12 && state.today.dayOfMonth > 24

@Composable
private fun Header(state: AdventUiState) {
    val opened = state.opened.size
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // made in Canva; it carries the title itself
        Image(
            painterResource(R.drawable.advent_header),
            contentDescription = "Adventskalender – jeden Tag eine kleine Überraschung",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f)
                .shadow(12.dp, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, Gold.copy(alpha = 0.35f), RoundedCornerShape(18.dp)),
        )
        Text(
            when {
                state.today.monthValue == 11 -> "Ab dem 1. Dezember öffnet sich jeden Tag ein Türchen"
                state.today.monthValue == 12 && state.today.dayOfMonth <= 24 -> "Heute ist Türchen ${state.today.dayOfMonth} dran"
                else -> "Frohe Weihnachten! Alle Türchen sind offen"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Cream.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            "$opened von 24 geöffnet",
            style = MaterialTheme.typography.labelMedium,
            color = Gold,
            modifier = Modifier
                .padding(top = 10.dp)
                .clip(CanopyPillShape)
                .border(1.dp, Gold.copy(alpha = 0.5f), CanopyPillShape)
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun Door(
    door: AdventDoor,
    style: DoorStyle,
    open: Boolean,
    today: Boolean,
    cover: String?,
    wide: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val swing = remember { Animatable(if (open) 1f else 0f) }
    LaunchedEffect(open) {
        if (open && swing.value < 1f) swing.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 60f))
    }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val pulse = if (today && !open) {
        rememberInfiniteTransition(label = "today").animateFloat(
            initialValue = 1f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "pulse",
        ).value
    } else {
        1f
    }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = pulse; scaleY = pulse; translationX = shake.value }
            .clickable {
                onClick()
                if (!open) scope.launch {
                    // a locked door rattles; an opening one just swings
                    for (x in listOf(8f, -8f, 5f, -5f, 0f)) shake.animateTo(x, tween(45))
                }
            },
    ) {
        // behind the door: the cover
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(Color(0xFF050A18)),
        ) {
            if (cover != null) {
                AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))))
            // narrow doors keep only the picture; the card tells the rest
            if (wide) Column(modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                Text(door.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Gold, fontWeight = FontWeight.Bold)
                Text(door.title, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "${door.day}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            )
        }
        // the door, hinged on its left edge
        if (swing.value < 0.98f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        rotationY = -105f * swing.value
                        cameraDistance = 14f * density
                    }
                    .shadow(if (today) 10.dp else 3.dp, shape)
                    .clip(shape)
                    .background(Brush.linearGradient(listOf(style.face, style.face.copy(alpha = 0.82f).compositeOverBlack())))
                    .border(if (today) 2.dp else 1.dp, if (today) Gold else style.ink.copy(alpha = 0.25f), shape)
                    .padding(6.dp)
                    .border(1.dp, style.ink.copy(alpha = 0.18f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${door.day}",
                    fontSize = if (door.day == 24) 52.sp else 34.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = style.ink,
                )
                Icon(
                    if (door.day % 3 == 0) PhosphorIcons.Fill.Star else PhosphorIcons.Fill.Snowflake,
                    contentDescription = null,
                    tint = style.ink.copy(alpha = 0.35f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(12.dp),
                )
                // the little knob
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 4.dp)
                        .size(6.dp)
                        .clip(CanopyPillShape)
                        .background(Gold),
                )
            }
        }
    }
}

private fun Color.compositeOverBlack(): Color = Color(red * alpha, green * alpha, blue * alpha, 1f)

@Composable
private fun DoorCard(door: AdventDoor, cover: String?, busy: Boolean, error: String?, onMore: () -> Unit, onListen: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(200.dp)
                .shadow(16.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(Night),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) {
                AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text("${door.day}", fontSize = 64.sp, fontWeight = FontWeight.ExtraBold, color = Gold)
            }
        }
        Text(
            "TÜRCHEN ${door.day} · ${door.label.uppercase()}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Gold,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            door.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Cream,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(door.teaser, style = MaterialTheme.typography.bodyMedium, color = Cream.copy(alpha = 0.7f), textAlign = TextAlign.Center)
        Row(
            modifier = Modifier
                .padding(top = 22.dp)
                .clip(CanopyPillShape)
                .background(Gold)
                .clickable(enabled = !busy, onClick = onListen)
                .padding(horizontal = 28.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(color = Night, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
                Icon(PhosphorIcons.Fill.Play, contentDescription = null, tint = Night, modifier = Modifier.size(18.dp))
            }
            Text(
                if (door.content is AdventContent.Show || door.content is AdventContent.Playlist) "Öffnen" else "Anhören",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Night,
            )
        }
        val more = door.content
        if (more is AdventContent.ArdEpisode || more is AdventContent.YouTube) {
            Text(
                if (more is AdventContent.YouTube) "Mehr von ${more.channel}" else "Mehr aus der Sendung",
                style = MaterialTheme.typography.labelLarge,
                color = Cream.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 12.dp).clip(CanopyPillShape).clickable(onClick = onMore).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = Cream.copy(alpha = 0.8f), modifier = Modifier.padding(top = 10.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Slow snow over the whole page, drawn on one canvas. */
@Composable
private fun Snowfall(modifier: Modifier, count: Int = 70, strength: Float = 1f) {
    val flakes = remember {
        val r = Random(24)
        List(count) { floatArrayOf(r.nextFloat(), r.nextFloat(), 0.4f + r.nextFloat() * 0.6f, r.nextFloat() * 6.28f) }
    }
    val time by rememberInfiniteTransition(label = "snow").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(40_000, easing = LinearEasing)),
        label = "t",
    )
    Canvas(modifier) {
        flakes.forEach { (x0, y0, depth, phase) ->
            val y = ((y0 + time * 6f * depth) % 1f) * size.height
            val x = (x0 * size.width + sin(time * 40f * depth + phase) * 14f * density)
            drawCircle(Color.White.copy(alpha = (0.25f + 0.45f * depth) * strength), radius = (1.2f + 2.2f * depth) * density * (0.5f + 0.5f * strength), center = Offset(x, y))
        }
    }
}

/** The entry on the start page and on "Entdecken" during the season: the Canva header
 * with today's door as a badge. */
@Composable
fun AdventBanner(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(3f)
            .shadow(8.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(NightMid)
            .clickable(onClick = onClick),
    ) {
        Image(
            painterResource(R.drawable.advent_header),
            contentDescription = "Adventskalender",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        val today = adventToday()
        Text(
            if (today.monthValue == 12 && today.dayOfMonth <= 24) "Türchen ${today.dayOfMonth} ist offen" else "24 Türchen zum Hören",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Night,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
                .clip(CanopyPillShape)
                .background(Gold)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
