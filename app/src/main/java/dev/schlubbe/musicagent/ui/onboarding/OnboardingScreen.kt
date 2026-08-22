package dev.schlubbe.musicagent.ui.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.schlubbe.musicagent.ui.components.CanopyButton
import dev.schlubbe.musicagent.ui.components.CanopyButtonVariant
import dev.schlubbe.musicagent.ui.components.WaveformLogoBadge
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Canopy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class TutorialSlide(val iconName: String, val title: String, val description: String)

private val SLIDES = listOf(
    TutorialSlide(
        "waveform",
        "Alles an einem Ort",
        "SoundCloud und YouTube Music gemeinsam durchsuchen und abspielen — ohne zwischen Apps zu wechseln.",
    ),
    TutorialSlide(
        "download-simple",
        "Nimm deine Musik mit",
        "Lade Titel und Playlists herunter und höre sie auch offline oder im Datensparmodus.",
    ),
    TutorialSlide(
        "pencil-simple",
        "Mach es dir zu eigen",
        "Eigene Cover, Beschreibungen, Farben und Tags für jede Playlist.",
    ),
    TutorialSlide(
        "sliders-horizontal",
        "Dein Klang, dein Raum",
        "3D-Sound-Vorlagen von Kino bis Rave — passend zu jeder Hörsituation.",
    ),
)

/** First-run flow: intro animation once, then a 4-slide tutorial -- combined
 * in one composable with an internal state switch, matching the design
 * handoff's own "Music Agent Onboarding.dc.html" (which prototypes all first-
 * impression moments on one frame rather than splitting them across files). */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    var showTutorial by remember { mutableStateOf(false) }
    if (showTutorial) {
        TutorialPager(onFinished = onFinished)
    } else {
        IntroAnimation(onContinue = { showTutorial = true })
    }
}

@Composable
private fun IntroAnimation(onContinue: () -> Unit) {
    var logoIn by remember { mutableStateOf(false) }
    var textIn by remember { mutableStateOf(false) }
    var trackIn by remember { mutableStateOf(false) }
    var buttonIn by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        logoIn = true
        delay(500)
        textIn = true
        delay(400)
        trackIn = true
        delay(1800)
        buttonIn = true
    }

    val logoScale by animateFloatAsState(if (logoIn) 1f else 0.7f, animationSpec = tween(900), label = "logoScale")
    val logoAlpha by animateFloatAsState(if (logoIn) 1f else 0f, animationSpec = tween(900), label = "logoAlpha")
    val textAlpha by animateFloatAsState(if (textIn) 1f else 0f, animationSpec = tween(800), label = "textAlpha")
    val trackProgress by animateFloatAsState(if (trackIn) 1f else 0f, animationSpec = tween(1800), label = "trackProgress")
    val buttonAlpha by animateFloatAsState(if (buttonIn) 1f else 0f, animationSpec = tween(800), label = "buttonAlpha")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Canopy.accent.copy(alpha = 0.16f), Canopy.bg),
                    radius = 900f,
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            WaveformLogoBadge(modifier = Modifier.scale(logoScale).alpha(logoAlpha))
            Spacer(modifier = Modifier.padding(top = 22.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(textAlpha)) {
                Text("Willkommen bei Grooveo", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(
                    "SoundCloud und YouTube Music, an einem Ort. Wir richten alles für dich ein.",
                    style = MaterialTheme.typography.labelMedium,
                    color = Canopy.neutral500,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp),
                )
            }
            Spacer(modifier = Modifier.padding(top = 22.dp))
            Box(
                modifier = Modifier
                    .width(140.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Canopy.neutral800)
                    .alpha(textAlpha),
            ) {
                Box(modifier = Modifier.fillMaxWidth(trackProgress).height(3.dp).background(Canopy.accent))
            }
        }
        CanopyButton(
            text = "Los geht's",
            onClick = onContinue,
            block = true,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp, vertical = 36.dp)
                .alpha(buttonAlpha),
        )
    }
}

@Composable
private fun TutorialPager(onFinished: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { SLIDES.size })
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().background(Canopy.bg)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                "Überspringen",
                color = Canopy.accent,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable(onClick = onFinished),
            )
        }

        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            val slide = SLIDES[page]
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier.size(88.dp).clip(CircleShape).background(Canopy.accent800),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(phosphorIcon(slide.iconName), contentDescription = null, tint = Canopy.accent300, modifier = Modifier.size(36.dp))
                }
                Spacer(modifier = Modifier.padding(top = 22.dp))
                Text(slide.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(
                    slide.description,
                    style = MaterialTheme.typography.labelMedium,
                    color = Canopy.neutral500,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        // Dot-pager on its own reserved line, per the handoff's own implementation
        // note: at this viewport width the prototype's dots overlapped the last
        // slide's description text when they shared a flex line with it.
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(SLIDES.size) { index ->
                val active = index == pagerState.currentPage
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.5.dp)
                        .width(if (active) 20.dp else 6.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (active) Canopy.accent else Canopy.neutral700),
                )
            }
        }

        val isLast = pagerState.currentPage == SLIDES.lastIndex
        CanopyButton(
            text = if (isLast) "Los geht's" else "Weiter",
            onClick = {
                if (isLast) {
                    onFinished()
                } else {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }
            },
            block = true,
            variant = CanopyButtonVariant.Primary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 30.dp),
        )
    }
}
