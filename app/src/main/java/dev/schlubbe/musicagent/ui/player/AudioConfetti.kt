package dev.schlubbe.musicagent.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import dev.schlubbe.musicagent.playback.VisualizerFrame
import dev.schlubbe.musicagent.ui.theme.Canopy
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Where the audio-reactive confetti should be emitted from, in **window**
 * coordinates. Reported by whichever composable owns each anchor via
 * [Modifier.onGloballyPositioned], because the emitters and the surface that draws
 * them live in completely different parts of the tree: the confetti has to be drawn
 * at window level (see [AudioConfettiHost]) while the things it appears to come from
 * are a small overlay inside the artwork and two buttons inside the mini player. */
class AudioConfettiState {
    /** Centre of the Player screen's visualizer overlay - the origin of the big
     * spray while the Player is open. */
    var playerAnchor by mutableStateOf<Offset?>(null)

    /** Centre of the mini player's play/pause button. */
    var miniPlayAnchor by mutableStateOf<Offset?>(null)

    /** Centre of the mini player's like (heart) button. */
    var miniLikeAnchor by mutableStateOf<Offset?>(null)
}

val LocalAudioConfetti = staticCompositionLocalOf { AudioConfettiState() }

/** Reports this element's centre in window coordinates into [set]. */
fun Modifier.reportConfettiAnchor(set: (Offset) -> Unit): Modifier =
    onGloballyPositioned { coordinates ->
        val position = coordinates.positionInWindow()
        set(
            Offset(
                position.x + coordinates.size.width / 2f,
                position.y + coordinates.size.height / 2f,
            ),
        )
    }

/** Hard cap on simultaneously-live pieces. Recycled oldest-first, so when a very loud
 * passage wants more than this the pieces dropped are always the oldest and most
 * faded ones.
 *
 * Has to exceed the peak emission rate times a piece's lifetime, or the ring wraps
 * while pieces are still mid-flight and they vanish in front of the viewer rather than
 * fading out. At the configured rates a loud passage emits on the order of a thousand
 * pieces a second against a ~1.3s life, so the previous 750 recycled roughly half of
 * them early. */
private const val MAX_PIECES = 1600
private const val CONFETTI_COLOR_COUNT = 5

/**
 * Detects the drop in a track, so the spray can go genuinely extreme exactly there
 * rather than treating every loud moment the same.
 *
 * Built on how a drop is actually constructed, which is also what the MIR literature
 * keys on: a build-up raises overall energy while deliberately *removing* the bass and
 * kick, and the drop is the moment that bass slams back in. So rather than looking for
 * "loud", this watches for the shape - a sustained stretch where the fast overall
 * energy runs above its own long-term average while fast bass sits well *below* its
 * long-term average (the build-up), which arms the detector; then a sharp bass jump
 * back over that long-term average fires it.
 *
 * Comparing each fast envelope against its own slow envelope, rather than against
 * fixed thresholds, is what makes this work across mastering levels and genres. On
 * material with no build-up at all the detector simply never arms, and the spray
 * stays at its normal loudness-driven rate.
 */
private class DropDetector {
    private var levelFast = 0f
    private var levelSlow = 0f
    private var bassFast = 0f
    private var bassSlow = 0f
    private var buildUpSeconds = 0f
    private var sinceBuildUp = 99f
    private var cooldown = 0f

    /** 0f..1f, 1f right at the drop, decaying over a few seconds afterwards. */
    var intensity = 0f
        private set

    /** True for the single frame the drop fires, for a one-off burst. */
    var justFired = false
        private set

    fun reset() {
        levelFast = 0f; levelSlow = 0f; bassFast = 0f; bassSlow = 0f
        buildUpSeconds = 0f; sinceBuildUp = 99f; cooldown = 0f
        intensity = 0f; justFired = false
    }

    fun update(dt: Float, level: Float, bass: Float) {
        justFired = false
        if (dt <= 0f) return
        levelFast += (level - levelFast) * (dt / 0.20f).coerceIn(0f, 1f)
        levelSlow += (level - levelSlow) * (dt / 6.0f).coerceIn(0f, 1f)
        bassFast += (bass - bassFast) * (dt / 0.12f).coerceIn(0f, 1f)
        bassSlow += (bass - bassSlow) * (dt / 6.0f).coerceIn(0f, 1f)

        // Build-up: energy up, bass conspicuously missing.
        val inBuildUp = levelFast > levelSlow * 1.03f && bassFast < bassSlow * 0.82f && levelFast > 0.10f
        if (inBuildUp) {
            buildUpSeconds += dt
            sinceBuildUp = 0f
        } else {
            sinceBuildUp += dt
        }

        if (cooldown > 0f) cooldown -= dt
        // Armed by a long enough build-up that ended very recently; fired by the bass
        // coming back hard.
        val armed = buildUpSeconds > 1.2f && sinceBuildUp < 2.5f
        val bassSlam = bassFast > bassSlow * 1.45f && bassFast > 0.28f
        if (armed && bassSlam && cooldown <= 0f) {
            intensity = 1f
            justFired = true
            cooldown = 8f
            buildUpSeconds = 0f
        }
        if (intensity > 0f) intensity = (intensity - dt / 4.5f).coerceAtLeast(0f)
    }
}

private class ConfettiPiece {
    var x = 0f
    var y = 0f
    var vx = 0f
    var vy = 0f
    var rot = 0f
    var vrot = 0f
    var life = 0f
    var maxLife = 1f
    var halfW = 0f
    var halfH = 0f
    var colorIndex = 0
}

/** The confetti simulation. Plain mutable objects rather than Compose state: this is
 * advanced and read from inside a draw lambda, and using snapshot state there would
 * invalidate the very phase that is writing it. */
private class ConfettiField {
    val pieces = Array(MAX_PIECES) { ConfettiPiece() }
    private var nextSlot = 0
    var lastFrameNanos = 0L
    /** The clock reading at the previous draw, for deriving dt. */
    var lastDrawMs = Float.NaN
    var spawnAccumulator = 0f
    var burstCooldownMs = 0f
    private val random = Random(0x51F7)

    /** Drops every live piece at once. Used when the effect is switched off (another
     * visualizer variant picked, playback stopped): the frame clock stops with it, so
     * anything still on screen would otherwise be frozen in place forever rather than
     * finishing its flight. */
    fun clear() {
        for (piece in pieces) piece.life = 0f
        spawnAccumulator = 0f
        burstCooldownMs = 0f
        lastDrawMs = Float.NaN
    }

    /** Emits [count] pieces from ([x], [y]) into a cone of [spreadRad] centred on
     * [angleRad] (screen coordinates, so -PI/2 points straight up). */
    fun spawn(
        count: Int,
        x: Float,
        y: Float,
        speed: Float,
        angleRad: Float,
        spreadRad: Float,
        pieceSize: Float,
        life: Float,
    ) {
        repeat(count) {
            val piece = pieces[nextSlot]
            nextSlot = (nextSlot + 1) % MAX_PIECES
            val angle = angleRad + (random.nextFloat() - 0.5f) * spreadRad
            val magnitude = speed * (0.55f + random.nextFloat() * 0.75f)
            piece.x = x
            piece.y = y
            piece.vx = cos(angle) * magnitude
            piece.vy = sin(angle) * magnitude
            piece.rot = random.nextFloat() * 360f
            piece.vrot = (random.nextFloat() - 0.5f) * 1_100f
            piece.maxLife = life * (0.7f + random.nextFloat() * 0.6f)
            piece.life = piece.maxLife
            piece.halfW = pieceSize * (0.7f + random.nextFloat() * 0.7f)
            piece.halfH = piece.halfW * (1.5f + random.nextFloat() * 1.3f)
            piece.colorIndex = random.nextInt(CONFETTI_COLOR_COUNT)
        }
    }

    fun advance(dt: Float, gravity: Float) {
        // Frame-rate-independent exponential drag, so a dropped frame doesn't let
        // pieces sail further than they should. Kept light on purpose: at 0.055 per
        // frame a piece keeps only ~3% of its speed after a second, which stalled
        // everything right on top of the emitter and read as one dense clump rather
        // than a spray.
        val drag = 1f - (0.012f * (dt / 0.016f)).coerceIn(0f, 0.9f)
        for (piece in pieces) {
            if (piece.life <= 0f) continue
            piece.vy += gravity * dt
            piece.vx *= drag
            piece.vy *= drag
            piece.x += piece.vx * dt
            piece.y += piece.vy * dt
            piece.rot += piece.vrot * dt
            piece.life -= dt
        }
    }
}

/**
 * Window-level audio-reactive confetti for the `pulse` visualizer.
 *
 * Drawn here, at the app root, rather than inside the visualizer itself, because
 * every surface it should cover is behind a clipping ancestor: the Player's overlay
 * lives inside a `clip(RoundedCornerShape(...))` scrim over the artwork, and the mini
 * player's buttons sit inside a `clip(CanopyPillShape)` surface - a clipped parent
 * clips its children's drawing however the child is sized. (The same reason
 * [dev.schlubbe.musicagent.ui.components.CanopyOverlayHost] exists for the one-shot
 * like/follow bursts.) At window level the spray can cross the whole player.
 *
 * Two emission modes, switched by whether the Player screen is open:
 * - **Player open** - a wide radial spray centred on the visualizer overlay, thrown
 *   far enough to cover a good part of the screen. This is meant to be the most
 *   extreme of the five variants, so it is deliberately louder than the rest.
 * - **Player closed** - two narrow upward jets, one from the mini player's play
 *   button and one from its like button, fired like flamethrowers so the confetti
 *   shoots up over the content above the bar.
 *
 * In both cases the rate is quadratic in loudness (so quiet passages stay nearly
 * clear while loud ones genuinely fill the view), with an extra burst whenever the
 * energy jumps clear of its own rolling baseline - which is what makes drops and beat
 * hits land as a visible pop.
 */
@Composable
fun AudioConfettiHost(
    state: AudioConfettiState,
    variant: String,
    isPlaying: Boolean,
    frame: State<VisualizerFrame>,
    playerOpen: Boolean,
) {
    val active = variant == "pulse" && isPlaying
    val field = remember { ConfettiField() }
    val drop = remember { DropDetector() }
    // Own frame clock: the simulation has to advance every frame, which no state
    // change would otherwise trigger. Stops entirely when inactive rather than
    // holding a 60fps frame request open for the whole session.
    val frameMs = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active) {
        if (!active) {
            field.lastFrameNanos = 0L
            // Wipe whatever is still in flight. The clock stops with the effect, so
            // without this everything on screen freezes mid-air and stays there after
            // switching to another visualizer or stopping playback.
            field.clear()
            drop.reset()
            frameMs.floatValue += 1f
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { now ->
                val previous = field.lastFrameNanos
                field.lastFrameNanos = now
                if (previous != 0L) {
                    frameMs.floatValue += ((now - previous) / 1_000_000f).coerceIn(0f, 48f)
                }
            }
        }
    }

    val colors = arrayOf(
        Canopy.accent2,
        Canopy.accent,
        Canopy.accent200,
        Canopy.accent600,
        Color.White.copy(alpha = 0.92f),
    )
    var hostOrigin by remember { mutableStateOf(Offset.Zero) }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { hostOrigin = it.positionInWindow() },
    ) {
        // Reading the clock inside the draw lambda is what re-runs this block each
        // frame without recomposing anything.
        val nowMs = frameMs.floatValue
        val previousMs = field.lastDrawMs
        field.lastDrawMs = nowMs
        // NaN on the very first draw, and a second draw within one frame yields 0 -
        // both correctly produce a no-op step rather than a jump.
        val dt = if (previousMs.isNaN()) 0f else ((nowMs - previousMs) / 1000f).coerceIn(0f, 0.048f)

        val reference = size.minDimension
        if (active && reference > 0f) {
            val liveFrame = frame.value
            val bass = liveFrame.bass

            drop.update(dt, liveFrame.levelAbsolute, liveFrame.bassAbsolute)
            // The drop is the one moment the spray is allowed to be absurd: several
            // times the normal rate, thrown harder and larger, plus a single huge
            // burst on the frame it lands.
            val dropBoost = 1f + 5.5f * drop.intensity
            val dropSize = 1f + 0.9f * drop.intensity
            val dropSpeed = 1f + 0.8f * drop.intensity

            // Spectral flux from the audio thread, not a loudness reading derived here.
            // Driving emission off the bass *level* produced a continuous drizzle,
            // because the level barely dips between hits - so the spray looked constant
            // and unrelated to the beat. An earlier attempt to recover the beat locally,
            // by rectifying bass against a rolling baseline, could not fix that either:
            // the input it worked from was an absolute dB reading whose entire
            // kick-to-kick variation was about 15% of scale, so the "kick" it recovered
            // was mostly the noise floor. Flux is measured where the spectrum actually
            // is - see VisualizerFrame.bassOnset.
            val kick = liveFrame.bassOnset

            if (field.burstCooldownMs > 0f) field.burstCooldownMs -= dt * 1000f
            val isOnset = kick > 0.45f && field.burstCooldownMs <= 0f

            // The drop itself: one mass of confetti in a single frame, so the moment
            // lands rather than merely ramping up.
            if (drop.justFired) {
                val origins = if (playerOpen) {
                    listOfNotNull(state.playerAnchor ?: Offset(size.width / 2f, size.height * 0.4f))
                } else {
                    listOfNotNull(state.miniPlayAnchor, state.miniLikeAnchor)
                }
                origins.forEach { origin ->
                    val x = origin.x - hostOrigin.x
                    val y = origin.y - hostOrigin.y
                    field.spawn(
                        count = if (playerOpen) 220 else 110,
                        x = x,
                        y = y,
                        speed = reference * 1.5f,
                        angleRad = if (playerOpen) 0f else -PI.toFloat() / 2f,
                        spreadRad = if (playerOpen) 2f * PI.toFloat() else 0.7f,
                        pieceSize = reference * 0.0045f,
                        life = 1.8f,
                    )
                }
            }

            if (playerOpen) {
                val anchor = (state.playerAnchor ?: Offset(size.width / 2f, size.height * 0.4f)) - hostOrigin
                // Full-circle spray, thrown hard enough to cross a good part of the
                // screen - this is the "most extreme visualizer" mode.
                // Two terms on purpose. The kick term is the larger and is what makes
                // the beat legible; the bass-level term is a floor that keeps the spray
                // alive through passages with no percussion at all. That floor used to
                // be twenty times smaller, which meant a track's non-percussive stretches
                // went completely dead - and a spray that is heavy for ten seconds and
                // then absent for thirty reads as random rather than as reactive.
                // Squaring both sharpens the contrast between quiet and loud.
                field.spawnAccumulator += (620f * kick * kick + 240f * bass * bass) * dropBoost * dt
                val steady = field.spawnAccumulator.toInt()
                if (steady > 0) {
                    field.spawnAccumulator -= steady
                    field.spawn(
                        count = steady,
                        x = anchor.x,
                        y = anchor.y,
                        speed = reference * (0.5f + 1.2f * kick) * dropSpeed,
                        angleRad = 0f,
                        spreadRad = 2f * PI.toFloat(),
                        pieceSize = reference * 0.0034f * dropSize,
                        life = 1.3f,
                    )
                }
                if (isOnset) {
                    field.spawn(
                        count = (30 + kick * 130f).toInt(),
                        x = anchor.x,
                        y = anchor.y,
                        speed = reference * (0.7f + 1.3f * kick) * dropSpeed,
                        angleRad = 0f,
                        spreadRad = 2f * PI.toFloat(),
                        pieceSize = reference * 0.0038f * dropSize,
                        life = 1.4f,
                    )
                    field.burstCooldownMs = 110f
                }
            } else {
                // Flamethrower mode: narrow cones straight up (-PI/2) from the two
                // mini-player buttons, fast enough to clear the bar by a long way.
                val jets = listOfNotNull(state.miniPlayAnchor, state.miniLikeAnchor)
                if (jets.isNotEmpty()) {
                    // Same kick-driven emission as the player spray - the jets pulse
                    // with the beat instead of running as a steady stream.
                    field.spawnAccumulator += (520f * kick * kick + 200f * bass * bass) * dropBoost * dt
                    val steady = field.spawnAccumulator.toInt()
                    if (steady > 0) {
                        field.spawnAccumulator -= steady
                        val perJet = (steady / jets.size).coerceAtLeast(1)
                        jets.forEach { jet ->
                            field.spawn(
                                count = perJet,
                                x = jet.x - hostOrigin.x,
                                y = jet.y - hostOrigin.y,
                                speed = reference * (0.55f + 0.95f * kick) * dropSpeed,
                                angleRad = -PI.toFloat() / 2f,
                                spreadRad = 0.42f,
                                pieceSize = reference * 0.0032f * dropSize,
                                life = 1.5f,
                            )
                        }
                    }
                    if (isOnset) {
                        jets.forEach { jet ->
                            field.spawn(
                                count = (22 + kick * 85f).toInt(),
                                x = jet.x - hostOrigin.x,
                                y = jet.y - hostOrigin.y,
                                speed = reference * (0.75f + 1.0f * kick) * dropSpeed,
                                angleRad = -PI.toFloat() / 2f,
                                spreadRad = 0.5f,
                                pieceSize = reference * 0.0036f * dropSize,
                                life = 1.6f,
                            )
                        }
                        field.burstCooldownMs = 110f
                    }
                }
            }
        }

        if (reference > 0f) {
            field.advance(dt, gravity = reference * 0.75f)
        }
        drawPieces(field, colors)
    }
}

private fun DrawScope.drawPieces(field: ConfettiField, colors: Array<Color>) {
    for (piece in field.pieces) {
        if (piece.life <= 0f) continue
        val lifeFraction = (piece.life / piece.maxLife).coerceIn(0f, 1f)
        translate(left = piece.x, top = piece.y) {
            rotate(degrees = piece.rot, pivot = Offset.Zero) {
                drawRoundRect(
                    color = colors[piece.colorIndex].copy(alpha = lifeFraction),
                    topLeft = Offset(-piece.halfW, -piece.halfH),
                    size = Size(piece.halfW * 2f, piece.halfH * 2f),
                    cornerRadius = CornerRadius(piece.halfW * 0.5f, piece.halfW * 0.5f),
                )
            }
        }
    }
}
