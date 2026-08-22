package dev.schlubbe.musicagent.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.schlubbe.musicagent.playback.EqPreset
import dev.schlubbe.musicagent.playback.Sound3dPreset
import dev.schlubbe.musicagent.ui.components.CanopyChip
import dev.schlubbe.musicagent.ui.components.CanopyIconButton
import dev.schlubbe.musicagent.ui.components.CanopySectionHeader
import dev.schlubbe.musicagent.ui.components.CanopyToggle
import dev.schlubbe.musicagent.ui.components.canopyCard
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Canopy
import kotlin.math.round

// Design source: design_handoff_grooveo/GrooveoApp.dc.html lines 839-910 ("EQUALIZER").
// Band layout + preset gains are copied verbatim from the handoff / README, and mirror
// EqualizerController.kt's own EqPreset enum (FLAT / BASS_BOOST / TREBLE_BOOST / VOCAL).
// "Eigen" (custom) has no backing EqPreset value -- see the KDoc on EqualizerScreen for
// exactly what's UI-only here vs. what's real.

internal data class EqBandSpec(val hz: String, val role: String)

internal val EQ_BAND_SPECS = listOf(
    EqBandSpec("60 Hz", "Sub"),
    EqBandSpec("230 Hz", "Bass"),
    EqBandSpec("910 Hz", "Mitten"),
    EqBandSpec("3,6 kHz", "Präsenz"),
    EqBandSpec("14 kHz", "Höhen"),
)

internal val EQ_PRESET_GAINS: Map<EqPreset, List<Float>> = mapOf(
    EqPreset.FLAT to listOf(0f, 0f, 0f, 0f, 0f),
    EqPreset.BASS_BOOST to listOf(9f, 6f, 0f, -1f, 0f),
    EqPreset.TREBLE_BOOST to listOf(0f, -1f, 0f, 6f, 9f),
    EqPreset.VOCAL to listOf(-3f, 0f, 5f, 4f, -2f),
)

internal val EQ_PRESET_ORDER = listOf(
    EqPreset.FLAT to "Flach",
    EqPreset.BASS_BOOST to "Bass-Boost",
    EqPreset.TREBLE_BOOST to "Höhen-Boost",
    EqPreset.VOCAL to "Vocal",
)

internal const val EQ_MIN_DB = -12f
internal const val EQ_MAX_DB = 12f
private const val PREAMP_MIN_DB = -12f
private const val PREAMP_MAX_DB = 6f

internal fun eqPresetLabel(preset: EqPreset): String =
    EQ_PRESET_ORDER.firstOrNull { it.first == preset }?.second ?: "Flach"

private fun formatDb(value: Float): String {
    val rounded = round(value).toInt()
    return if (rounded > 0) "+$rounded dB" else "$rounded dB"
}

/**
 * Full-screen Equalizer (design_handoff_grooveo section "10 Equalizer" -- new screen,
 * no prior Compose implementation existed).
 *
 * IMPORTANT -- what's real vs. UI-only, since the data layer only supports a 4-value
 * [EqPreset] enum today, not per-band gains or a pre-amp:
 * - The preset chips (Flach/Bass-Boost/Höhen-Boost/Vocal) are REAL: they call
 *   [SettingsViewModel.onEqPresetChanged], which persists via SettingsRepository and is
 *   applied to the platform Equalizer by PlaybackService's collector
 *   (`settingsRepository.eqPreset.collect { equalizerController.applyPreset(it) }`).
 * - The header on/off Toggle is REAL in effect (best-effort): switching it off applies
 *   [EqPreset.FLAT] (silences all bands) via the same real path, and switching back on
 *   re-applies whichever preset was active before. There is no dedicated "enabled" flag
 *   in the data layer, so this is implemented as a preset swap, not a true bypass toggle.
 * - The five per-band vertical faders and the pre-amp slider are UI-ONLY. Dragging them
 *   updates local Compose state (and flips the active preset chip to "Eigen"), but does
 *   NOT reach [dev.schlubbe.musicagent.playback.EqualizerController] or persist anywhere
 *   -- there is no per-band gain storage in SettingsRepository and no EqPreset.CUSTOM
 *   value to persist it under. See the task report for exactly what a real implementation
 *   needs in the data layer.
 */
@Composable
fun EqualizerScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    var eqOn by remember { mutableStateOf(true) }
    var presetBeforeOff by remember { mutableStateOf(uiState.eqPreset) }
    var isCustom by remember { mutableStateOf(false) }
    var gains by remember { mutableStateOf(EQ_PRESET_GAINS[uiState.eqPreset] ?: List(5) { 0f }) }
    var preampDb by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(uiState.eqPreset) {
        if (!isCustom) gains = EQ_PRESET_GAINS[uiState.eqPreset] ?: List(5) { 0f }
    }

    fun selectPreset(preset: EqPreset) {
        isCustom = false
        gains = EQ_PRESET_GAINS[preset] ?: List(5) { 0f }
        presetBeforeOff = preset
        viewModel.onEqPresetChanged(preset)
    }

    fun onEqToggle(on: Boolean) {
        eqOn = on
        if (!on) {
            presetBeforeOff = uiState.eqPreset
            viewModel.onEqPresetChanged(EqPreset.FLAT)
        } else {
            viewModel.onEqPresetChanged(presetBeforeOff)
        }
    }

    fun onBandDrag(index: Int, value: Float) {
        if (!eqOn) return
        isCustom = true
        gains = gains.toMutableList().also { it[index] = value.coerceIn(EQ_MIN_DB, EQ_MAX_DB) }
    }

    fun onReset() {
        isCustom = false
        gains = EQ_PRESET_GAINS[EqPreset.FLAT] ?: List(5) { 0f }
        preampDb = 0f
        presetBeforeOff = EqPreset.FLAT
        viewModel.onEqPresetChanged(EqPreset.FLAT)
    }

    val presetLabel = if (isCustom) "Eigen" else eqPresetLabel(uiState.eqPreset)
    val sound3dOn = uiState.sound3dPreset != Sound3dPreset.DISABLED

    Scaffold(containerColor = Canopy.bg) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CanopyIconButton(icon = phosphorIcon("caret-left"), onClick = onNavigateBack, iconSize = 20.dp)
                Text(
                    "Equalizer",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 6.dp).weight(1f),
                )
                CanopyToggle(checked = eqOn, onCheckedChange = ::onEqToggle)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // — Frequenzverlauf —
                Column(modifier = Modifier.fillMaxWidth().canopyCard(padding = 16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column {
                            Text("FREQUENZVERLAUF", style = MaterialTheme.typography.titleSmall, color = Canopy.neutral400)
                            Spacer(modifier = Modifier.padding(top = 6.dp))
                            Text(presetLabel, style = MaterialTheme.typography.labelLarge, color = Canopy.accent)
                        }
                        Text("±12 dB", style = MaterialTheme.typography.labelMedium, color = Canopy.neutral500)
                    }
                    Spacer(modifier = Modifier.padding(top = 10.dp))
                    EqCurveCanvas(
                        gains = gains,
                        enabled = eqOn,
                        modifier = Modifier.fillMaxWidth().height(104.dp),
                    )
                }

                // — Bands —
                Column(modifier = Modifier.fillMaxWidth().canopyCard(padding = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        EQ_BAND_SPECS.forEachIndexed { index, band ->
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(9.dp),
                            ) {
                                Text(formatDb(gains[index]), style = MaterialTheme.typography.labelMedium, color = Canopy.accent)
                                EqFader(
                                    value = gains[index],
                                    enabled = eqOn,
                                    onChange = { v -> onBandDrag(index, v) },
                                    modifier = Modifier.width(30.dp).height(158.dp),
                                )
                                Text(band.hz, style = MaterialTheme.typography.labelSmall, color = Canopy.neutral500)
                                Text(band.role, style = MaterialTheme.typography.labelSmall, color = Canopy.neutral400)
                            }
                        }
                    }
                }

                // — Vorverstärker —
                Column(modifier = Modifier.fillMaxWidth().canopyCard(padding = 16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Vorverstärker", style = MaterialTheme.typography.labelLarge)
                        Text(formatDb(preampDb), style = MaterialTheme.typography.labelMedium, color = Canopy.accent2)
                    }
                    PreampSlider(
                        value = preampDb,
                        enabled = eqOn,
                        onChange = { v ->
                            preampDb = v.coerceIn(PREAMP_MIN_DB, PREAMP_MAX_DB)
                            isCustom = true
                        },
                        modifier = Modifier.fillMaxWidth().height(24.dp),
                    )
                }

                // — Voreinstellungen —
                Column {
                    CanopySectionHeader(title = "Voreinstellungen", action = "Zurücksetzen", onActionClick = ::onReset)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            EQ_PRESET_ORDER.take(3).forEach { (preset, label) ->
                                CanopyChip(label = label, active = !isCustom && uiState.eqPreset == preset, onClick = { selectPreset(preset) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            EQ_PRESET_ORDER.drop(3).forEach { (preset, label) ->
                                CanopyChip(label = label, active = !isCustom && uiState.eqPreset == preset, onClick = { selectPreset(preset) })
                            }
                            CanopyChip(label = "Eigen", active = isCustom, onClick = { isCustom = true })
                        }
                    }
                }

                // — 3D-Sound —
                Row(
                    modifier = Modifier.fillMaxWidth().canopyCard(padding = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(phosphorIcon("circles-three"), contentDescription = null, tint = Canopy.accent, modifier = Modifier.size(20.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("3D-Sound", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "Räumliche Wiedergabe über Kopfhörer",
                            style = MaterialTheme.typography.bodySmall,
                            color = Canopy.neutral500,
                        )
                    }
                    CanopyToggle(
                        checked = sound3dOn,
                        onCheckedChange = { on ->
                            viewModel.onSound3dPresetChanged(if (on) Sound3dPreset.KINO else Sound3dPreset.DISABLED)
                        },
                    )
                }

                Text(
                    "Wirkt auf die Wiedergabe über den Android-System-Equalizer, sofern dein Gerät ihn unterstützt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Canopy.neutral500,
                )
                Spacer(modifier = Modifier.padding(bottom = 24.dp))
            }
        }
    }
}

@Composable
private fun EqCurveCanvas(gains: List<Float>, enabled: Boolean, modifier: Modifier = Modifier) {
    val lineColor = Canopy.accent
    val ringColor = Canopy.accent2
    val dotFill = Canopy.surface
    val dashColor = Canopy.neutral400
    val alphaMul = if (enabled) 1f else 0.4f

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val n = gains.size
        if (n == 0 || w <= 0f || h <= 0f) return@Canvas
        val inset = 10.dp.toPx()
        val xs = FloatArray(n) { i -> if (n == 1) w / 2f else w * i / (n - 1) }
        fun gainToY(g: Float) = h / 2f - (g / EQ_MAX_DB) * (h / 2f - inset)
        val ys = FloatArray(n) { gainToY(gains[it]) }

        drawLine(
            color = dashColor.copy(alpha = dashColor.alpha * alphaMul),
            start = Offset(0f, h / 2f),
            end = Offset(w, h / 2f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
        )

        val path = Path().apply {
            moveTo(xs[0], ys[0])
            for (i in 0 until n - 1) {
                val midX = (xs[i] + xs[i + 1]) / 2f
                cubicTo(midX, ys[i], midX, ys[i + 1], xs[i + 1], ys[i + 1])
            }
        }
        val fillPath = Path().apply {
            addPath(path)
            lineTo(xs[n - 1], h)
            lineTo(xs[0], h)
            close()
        }
        drawPath(fillPath, color = lineColor.copy(alpha = 0.16f * alphaMul))
        drawPath(path, color = lineColor.copy(alpha = alphaMul), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

        for (i in 0 until n) {
            drawCircle(dotFill.copy(alpha = alphaMul), radius = 5.dp.toPx(), center = Offset(xs[i], ys[i]))
            drawCircle(
                ringColor.copy(alpha = alphaMul),
                radius = 5.dp.toPx(),
                center = Offset(xs[i], ys[i]),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

@Composable
private fun EqFader(value: Float, enabled: Boolean, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val knobRadiusPx = with(density) { 12.dp.toPx() }
    var heightPx by remember { mutableFloatStateOf(0f) }
    val trackColor = Canopy.neutral300
    val knobColor = Canopy.accent
    val ringColor = Canopy.surface
    val alphaMul = if (enabled) 1f else 0.35f

    fun dbToY(db: Float): Float {
        val usable = (heightPx - knobRadiusPx * 2).coerceAtLeast(1f)
        return knobRadiusPx + usable * (1f - (db - EQ_MIN_DB) / (EQ_MAX_DB - EQ_MIN_DB))
    }
    fun yToDb(y: Float): Float {
        val usable = (heightPx - knobRadiusPx * 2).coerceAtLeast(1f)
        val t = ((y - knobRadiusPx) / usable).coerceIn(0f, 1f)
        return (EQ_MAX_DB - t * (EQ_MAX_DB - EQ_MIN_DB)).coerceIn(EQ_MIN_DB, EQ_MAX_DB)
    }

    Box(
        modifier = modifier
            .onSizeChanged { heightPx = it.height.toFloat() }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset -> onChange(round(yToDb(offset.y))) },
                    onDrag = { change, _ ->
                        change.consume()
                        onChange(round(yToDb(change.position.y)))
                    },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val centerY = dbToY(0f)
            val knobY = dbToY(value)
            drawLine(
                color = trackColor.copy(alpha = trackColor.alpha * alphaMul),
                start = Offset(cx, 0f),
                end = Offset(cx, size.height),
                strokeWidth = 6.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = knobColor.copy(alpha = alphaMul),
                start = Offset(cx, centerY),
                end = Offset(cx, knobY),
                strokeWidth = 6.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(color = ringColor.copy(alpha = alphaMul), radius = knobRadiusPx + 3.dp.toPx() / 2f, center = Offset(cx, knobY))
            drawCircle(color = knobColor.copy(alpha = alphaMul), radius = knobRadiusPx, center = Offset(cx, knobY))
        }
    }
}

@Composable
private fun PreampSlider(value: Float, enabled: Boolean, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val knobRadiusPx = with(density) { 12.dp.toPx() }
    var widthPx by remember { mutableFloatStateOf(0f) }
    val trackColor = Canopy.neutral300
    val fillColor = Canopy.accent2
    val ringColor = Canopy.surface
    val alphaMul = if (enabled) 1f else 0.35f

    fun dbToX(db: Float): Float {
        val usable = (widthPx - knobRadiusPx * 2).coerceAtLeast(1f)
        return knobRadiusPx + usable * ((db - PREAMP_MIN_DB) / (PREAMP_MAX_DB - PREAMP_MIN_DB))
    }
    fun xToDb(x: Float): Float {
        val usable = (widthPx - knobRadiusPx * 2).coerceAtLeast(1f)
        val t = ((x - knobRadiusPx) / usable).coerceIn(0f, 1f)
        return (PREAMP_MIN_DB + t * (PREAMP_MAX_DB - PREAMP_MIN_DB)).coerceIn(PREAMP_MIN_DB, PREAMP_MAX_DB)
    }

    Box(
        modifier = modifier
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset -> onChange(round(xToDb(offset.x))) },
                    onDrag = { change, _ ->
                        change.consume()
                        onChange(round(xToDb(change.position.x)))
                    },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val knobX = dbToX(value)
            drawLine(
                color = trackColor.copy(alpha = trackColor.alpha * alphaMul),
                start = Offset(0f, cy),
                end = Offset(size.width, cy),
                strokeWidth = 6.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = fillColor.copy(alpha = alphaMul),
                start = Offset(0f, cy),
                end = Offset(knobX, cy),
                strokeWidth = 6.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(color = ringColor.copy(alpha = alphaMul), radius = knobRadiusPx + 3.dp.toPx() / 2f, center = Offset(knobX, cy))
            drawCircle(color = fillColor.copy(alpha = alphaMul), radius = knobRadiusPx, center = Offset(knobX, cy))
        }
    }
}

/** Small static preview of a preset's curve, used by the Settings screen's
 * "Bänder einstellen" row so it doesn't duplicate the drawing logic above. */
@Composable
internal fun EqCurvePreview(gains: List<Float>, modifier: Modifier = Modifier) {
    EqCurveCanvas(gains = gains, enabled = true, modifier = modifier)
}
