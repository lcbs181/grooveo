package dev.schlubbe.musicagent.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import android.widget.Toast
import dev.schlubbe.musicagent.playback.Sound3dPreset
import dev.schlubbe.musicagent.playback.eq.EqBand
import dev.schlubbe.musicagent.playback.eq.EqProfile
import dev.schlubbe.musicagent.playback.eq.FilterType
import dev.schlubbe.musicagent.ui.components.CanopyButton
import dev.schlubbe.musicagent.ui.components.CanopyChip
import dev.schlubbe.musicagent.ui.components.CanopyIconButton
import dev.schlubbe.musicagent.ui.components.CanopySectionHeader
import dev.schlubbe.musicagent.ui.components.CanopyToggle
import dev.schlubbe.musicagent.ui.components.canopyCard
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Canopy
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** Pure mapping between graph pixels and frequency/gain (log frequency axis). Unit-tested. */
internal object EqGraph {
    const val MIN_F = 20.0
    const val MAX_F = 20000.0
    const val RANGE_DB = 15.0
    private val SPAN = log10(MAX_F / MIN_F)

    fun freqToX(f: Double, w: Float): Float = (log10(f / MIN_F) / SPAN * w).toFloat()
    fun xToFreq(x: Float, w: Float): Double = MIN_F * 10.0.pow((x / w).coerceIn(0f, 1f) * SPAN)
    fun dbToY(db: Double, h: Float): Float = (h / 2 - db / RANGE_DB * (h / 2)).toFloat()
    fun yToDb(y: Float, h: Float): Double = ((h / 2 - y) / (h / 2) * RANGE_DB).coerceIn(-RANGE_DB, RANGE_DB)

    /** Snaps to 3 significant digits (1 Hz below 100 Hz), as typed in EQ software. */
    fun roundFreq(f: Double): Double {
        val step = 10.0.pow((log10(f).toInt() - 2).coerceAtLeast(0).toDouble())
        return ((f / step).roundToInt() * step).coerceIn(MIN_F, MAX_F)
    }

    fun roundDb(db: Double): Double = (db * 2).roundToInt() / 2.0

    /** Index of the node nearest to ([x],[y]) within [radius] px, or -1. */
    fun hit(bands: List<EqBand>, x: Float, y: Float, w: Float, h: Float, radius: Float): Int {
        var best = -1
        var bestD = radius
        bands.forEachIndexed { i, b ->
            val d = hypot(x - freqToX(b.freq, w), y - dbToY(nodeDb(b), h))
            if (d <= bestD) { bestD = d; best = i }
        }
        return best
    }

    fun nodeDb(b: EqBand): Double = if (b.type.hasGain) b.gainDb else 0.0
}

internal fun formatFreq(f: Double): String =
    if (f >= 1000) String.format(Locale.GERMAN, "%.${if (f >= 10000) 1 else 2}f kHz", f / 1000).replace(",00 ", " ").replace(",0 ", " ")
    else "${f.roundToInt()} Hz"

private fun formatDb(db: Double): String = String.format(Locale.GERMAN, "%+.1f dB", db)

/**
 * Parametric equalizer (same DSP and presets as the desktop app, see
 * [dev.schlubbe.musicagent.playback.eq.ParametricEqAudioProcessor]): response graph
 * with draggable band nodes, per-band editor, preamp/limiter, bass tools, presets
 * and Equalizer APO / AutoEQ import/export via the clipboard.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun EqualizerScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val eq = uiState.eqProfile
    var selected by remember { mutableIntStateOf(0) }
    var showSave by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val sel = selected.coerceIn(0, (eq.bands.size - 1).coerceAtLeast(0))

    fun set(p: EqProfile) = viewModel.onEqProfileChanged(p)
    fun setBand(i: Int, f: (EqBand) -> EqBand) =
        set(eq.copy(name = "Eigen", bands = eq.bands.mapIndexed { j, b -> if (j == i) f(b).clamped() else b }))

    Scaffold(containerColor = Canopy.bg, contentWindowInsets = WindowInsets(0)) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CanopyIconButton(icon = phosphorIcon("caret-left"), onClick = onNavigateBack, iconSize = 20.dp)
                Text("Equalizer", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 6.dp).weight(1f))
                CanopyToggle(checked = eq.enabled, onCheckedChange = { set(eq.copy(enabled = it)) })
            }

            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // — Frequenzgang —
                Column(modifier = Modifier.fillMaxWidth().canopyCard(padding = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("FREQUENZGANG", style = MaterialTheme.typography.titleSmall, color = Canopy.neutral400)
                            Text(eq.name, style = MaterialTheme.typography.labelLarge, color = Canopy.accent, modifier = Modifier.padding(top = 4.dp))
                        }
                        Text("±15 dB", style = MaterialTheme.typography.labelMedium, color = Canopy.neutral500)
                    }
                    Spacer(Modifier.height(8.dp))
                    EqResponseGraph(
                        eq = eq,
                        selected = sel,
                        onSelect = { selected = it },
                        onMove = { i, f, db -> setBand(i) { b -> b.copy(freq = EqGraph.roundFreq(f), gainDb = if (b.type.hasGain) EqGraph.roundDb(db) else b.gainDb) } },
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                    )
                    Text(
                        "Punkt antippen zum Auswählen, ziehen für Frequenz und Pegel.",
                        style = MaterialTheme.typography.bodySmall, color = Canopy.neutral500, modifier = Modifier.padding(top = 6.dp),
                    )
                }

                // — Bänder —
                Column(modifier = Modifier.fillMaxWidth().canopyCard(padding = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        eq.bands.forEachIndexed { i, b ->
                            CanopyChip(label = "${i + 1} · ${formatFreq(b.freq)}", active = i == sel, onClick = { selected = i })
                        }
                        if (eq.bands.size < EqProfile.MAX_BANDS) {
                            CanopyChip(label = "+ Band", active = false, onClick = {
                                set(eq.copy(name = "Eigen", bands = eq.bands + EqBand(FilterType.PEAK, 1000.0, 0.0, 1.0)))
                                selected = eq.bands.size
                            })
                        }
                    }
                    eq.bands.getOrNull(sel)?.let { b ->
                        BandEditor(
                            band = b,
                            onChange = { nb -> setBand(sel) { nb } },
                            onDelete = { set(eq.copy(name = "Eigen", bands = eq.bands.filterIndexed { j, _ -> j != sel })) },
                        )
                    }
                }

                // — Vorverstärker —
                Column(modifier = Modifier.fillMaxWidth().canopyCard(padding = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LabeledSlider("Vorverstärker", formatDb(eq.preampDb), eq.preampDb.toFloat(), -24f..12f, Canopy.accent2) {
                        set(eq.copy(preampDb = EqGraph.roundDb(it.toDouble())))
                    }
                    val headroom = eq.peakBoostDb() + eq.preampDb
                    if (headroom > 0.05) {
                        Text(
                            String.format(Locale.GERMAN, "Bis zu %+.1f dB über 0 dBFS%s", headroom, if (eq.limiter) " – der Limiter fängt das ab." else " – Übersteuerung möglich."),
                            style = MaterialTheme.typography.bodySmall, color = Canopy.accent2,
                        )
                    }
                    CanopyButton(text = "Automatisch anpassen", onClick = { set(eq.withAutoPreamp()) })
                    ToggleRow("Limiter", "Verhindert Clipping bei starken Anhebungen (Look-ahead, −0,5 dBFS)", eq.limiter) { set(eq.copy(limiter = it)) }
                }

                // — Bass —
                Column(modifier = Modifier.fillMaxWidth().canopyCard(padding = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Bass", style = MaterialTheme.typography.labelLarge)
                    ToggleRow("Subsonic-Filter", "Hochpass 20 Hz, 24 dB/Okt. – unhörbares Rumpeln kostet Headroom", eq.subsonic) { set(eq.copy(subsonic = it)) }
                    ToggleRow("Loudness-Kompensation", "Gehörrichtig nach ISO 226: mehr Bass und Höhen bei leiser Wiedergabe", eq.loudness) { set(eq.copy(loudness = it)) }
                    LabeledSlider(
                        "Bass-Enhancer", if (eq.bassEnhance == 0.0) "Aus" else "${(eq.bassEnhance * 100).roundToInt()} %",
                        eq.bassEnhance.toFloat(), 0f..1f, Canopy.accent2,
                    ) { set(eq.copy(bassEnhance = (it * 100).roundToInt() / 100.0)) }
                    if (eq.bassEnhance > 0) {
                        LabeledSlider("Enhancer bis", "${eq.bassEnhanceFreq.roundToInt()} Hz", eq.bassEnhanceFreq.toFloat(), 40f..160f, Canopy.accent2) {
                            set(eq.copy(bassEnhanceFreq = it.roundToInt().toDouble()))
                        }
                    }
                    Text(
                        "Der Enhancer ergänzt Obertöne des Tiefbasses – das Ohr hört den Grundton mit, auch über Handy-Lautsprecher.",
                        style = MaterialTheme.typography.bodySmall, color = Canopy.neutral500,
                    )
                }

                // — Voreinstellungen —
                Column {
                    CanopySectionHeader(title = "Voreinstellungen", action = "Speichern", onActionClick = { showSave = true })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        EqProfile.PRESETS.forEach { p ->
                            CanopyChip(label = p.name, active = eq.name == p.name, onClick = { set(p.copy(enabled = true)); selected = 0 })
                        }
                        uiState.eqUserPresets.forEach { p ->
                            CanopyChip(
                                label = p.name,
                                active = eq.name == p.name,
                                onClick = { set(p.copy(enabled = true)); selected = 0 },
                                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = {
                                    viewModel.onDeleteEqPreset(p.name)
                                    Toast.makeText(context, "„${p.name}“ gelöscht", Toast.LENGTH_SHORT).show()
                                }),
                            )
                        }
                    }
                    if (uiState.eqUserPresets.isNotEmpty()) {
                        Text("Eigene Vorlagen lange drücken zum Löschen.", style = MaterialTheme.typography.bodySmall, color = Canopy.neutral500, modifier = Modifier.padding(top = 6.dp))
                    }
                }

                // — Import / Export —
                Column(modifier = Modifier.fillMaxWidth().canopyCard(padding = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Equalizer APO / AutoEQ", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Kopfhörer-Korrekturen von autoeq.app („ParametricEQ.txt“) einfügen oder das aktuelle Profil kopieren.",
                        style = MaterialTheme.typography.bodySmall, color = Canopy.neutral500,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CanopyChip(label = "Aus Zwischenablage", active = false, onClick = {
                            val text = clipboard.getText()?.text.orEmpty()
                            runCatching { EqProfile.parseApo(text) }
                                .onSuccess { set(it); selected = 0; Toast.makeText(context, "${it.bands.size} Filter importiert", Toast.LENGTH_SHORT).show() }
                                .onFailure { Toast.makeText(context, "Kein Equalizer-APO-Text in der Zwischenablage", Toast.LENGTH_SHORT).show() }
                        })
                        CanopyChip(label = "Kopieren", active = false, onClick = {
                            clipboard.setText(AnnotatedString(eq.toApoText()))
                            Toast.makeText(context, "Profil kopiert", Toast.LENGTH_SHORT).show()
                        })
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
                        Text("Räumliche Wiedergabe über Kopfhörer", style = MaterialTheme.typography.bodySmall, color = Canopy.neutral500)
                    }
                    CanopyToggle(
                        checked = uiState.sound3dPreset != Sound3dPreset.DISABLED,
                        onCheckedChange = { on -> viewModel.onSound3dPresetChanged(if (on) Sound3dPreset.KINO else Sound3dPreset.DISABLED) },
                    )
                }

                CanopySectionHeader(title = "", action = "Auf Flach zurücksetzen", onActionClick = { set(EqProfile.flat()); selected = 0 })
                Spacer(modifier = Modifier.padding(bottom = 24.dp))
            }
        }
    }

    if (showSave) {
        var name by remember { mutableStateOf(if (eq.name in EqProfile.PRESETS.map { it.name }) "" else eq.name) }
        AlertDialog(
            onDismissRequest = { showSave = false },
            containerColor = Canopy.surface,
            title = { Text("Vorlage speichern") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onSaveEqPreset(name.trim().ifEmpty { "Vorlage ${uiState.eqUserPresets.size + 1}" })
                    showSave = false
                }) { Text("Speichern", color = Canopy.accent) }
            },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("Abbrechen", color = Canopy.neutral500) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BandEditor(band: EqBand, onChange: (EqBand) -> Unit, onDelete: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterType.entries.forEach { t ->
            CanopyChip(label = t.label, active = band.type == t, onClick = { onChange(band.copy(type = t)) })
        }
    }
    val logMin = ln(EqGraph.MIN_F).toFloat()
    val logMax = ln(EqGraph.MAX_F).toFloat()
    LabeledSlider("Frequenz", formatFreq(band.freq), ln(band.freq).toFloat(), logMin..logMax, Canopy.accent) {
        onChange(band.copy(freq = EqGraph.roundFreq(kotlin.math.exp(it.toDouble()))))
    }
    if (band.type.hasGain) {
        LabeledSlider("Pegel", formatDb(band.gainDb), band.gainDb.toFloat(), -EqBand.MAX_GAIN.toFloat()..EqBand.MAX_GAIN.toFloat(), Canopy.accent) {
            onChange(band.copy(gainDb = EqGraph.roundDb(it.toDouble())))
        }
    }
    LabeledSlider("Güte (Q)", String.format(Locale.GERMAN, "%.2f", band.q), ln(band.q).toFloat(), ln(EqBand.MIN_Q).toFloat()..ln(EqBand.MAX_Q).toFloat(), Canopy.accent) {
        onChange(band.copy(q = (kotlin.math.exp(it.toDouble()) * 100).roundToInt() / 100.0))
    }
    if (band.type.hasSlope) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Steilheit", style = MaterialTheme.typography.labelMedium, color = Canopy.neutral500, modifier = Modifier.width(72.dp))
            (1..4).forEach { s -> CanopyChip(label = "${s * 12}", active = band.slope == s, onClick = { onChange(band.copy(slope = s)) }) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Aktiv", style = MaterialTheme.typography.labelMedium, color = Canopy.neutral500, modifier = Modifier.padding(end = 10.dp))
        CanopyToggle(checked = band.enabled, onCheckedChange = { onChange(band.copy(enabled = it)) })
        Spacer(Modifier.weight(1f))
        CanopyIconButton(icon = phosphorIcon("trash"), onClick = onDelete, iconSize = 18.dp)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: String,
    position: Float,
    range: ClosedFloatingPointRange<Float>,
    color: Color,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Canopy.neutral500)
            Text(value, style = MaterialTheme.typography.labelMedium, color = color)
        }
        Slider(
            value = position.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color, inactiveTrackColor = Canopy.neutral300),
            modifier = Modifier.height(32.dp),
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Canopy.neutral500)
        }
        CanopyToggle(checked = checked, onCheckedChange = onChange)
    }
}

/** Live response curve (without preamp: it only shifts the level) plus one node per band. */
@Composable
private fun EqResponseGraph(
    eq: EqProfile,
    selected: Int,
    onSelect: (Int) -> Unit,
    onMove: (Int, Double, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val line = Canopy.accent
    val node = Canopy.accent2
    val grid = Canopy.neutral300
    val fill = Canopy.surface
    val alpha = if (eq.enabled) 1f else 0.4f
    val current by rememberUpdatedState(eq)
    var dragging by remember { mutableIntStateOf(-1) }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    val i = EqGraph.hit(current.bands, o.x, o.y, size.width.toFloat(), size.height.toFloat(), 36.dp.toPx())
                    if (i >= 0) onSelect(i)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o ->
                        dragging = EqGraph.hit(current.bands, o.x, o.y, size.width.toFloat(), size.height.toFloat(), 36.dp.toPx())
                        if (dragging >= 0) onSelect(dragging)
                    },
                    onDragEnd = { dragging = -1 },
                    onDragCancel = { dragging = -1 },
                    onDrag = { change, _ ->
                        if (dragging < 0) return@detectDragGestures
                        change.consume()
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        onMove(dragging, EqGraph.xToFreq(change.position.x, w), EqGraph.yToDb(change.position.y, h))
                    },
                )
            },
    ) {
        val w = size.width
        val h = size.height
        // grid: decades and ±6/12 dB
        listOf(100.0, 1000.0, 10000.0).forEach { f ->
            val x = EqGraph.freqToX(f, w)
            drawLine(grid.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
        }
        listOf(-12.0, -6.0, 6.0, 12.0).forEach { db ->
            val y = EqGraph.dbToY(db, h)
            drawLine(grid.copy(alpha = 0.35f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        drawLine(grid, Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))

        val steps = (w / 3).toInt().coerceAtLeast(32)
        val path = Path()
        for (s in 0..steps) {
            val x = w * s / steps
            val db = if (eq.enabled) eq.responseDb(EqGraph.xToFreq(x, w)) - eq.preampDb else 0.0
            val y = EqGraph.dbToY(db.coerceIn(-30.0, 30.0), h).coerceIn(-h, 2 * h)
            if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val area = Path().apply { addPath(path); lineTo(w, h / 2); lineTo(0f, h / 2); close() }
        drawPath(area, line.copy(alpha = 0.14f * alpha))
        drawPath(path, line.copy(alpha = alpha), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

        eq.bands.forEachIndexed { i, b ->
            val c = Offset(EqGraph.freqToX(b.freq, w), EqGraph.dbToY(EqGraph.nodeDb(b), h))
            val r = (if (i == selected) 9.dp else 7.dp).toPx()
            val a = alpha * if (b.enabled) 1f else 0.4f
            drawCircle(if (i == selected) node.copy(alpha = a) else fill.copy(alpha = a), radius = r, center = c)
            drawCircle(node.copy(alpha = a), radius = r, center = c, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

/** Small static preview of the active curve for the Settings screen's EQ row. */
@Composable
internal fun EqCurvePreview(profile: EqProfile, modifier: Modifier = Modifier) {
    val line = Canopy.accent
    val grid = Canopy.neutral400
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawLine(grid, Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
        val path = Path()
        for (s in 0..24) {
            val x = w * s / 24
            val db = if (profile.enabled) profile.responseDb(EqGraph.xToFreq(x, w)) - profile.preampDb else 0.0
            val y = (h / 2 - db / EqGraph.RANGE_DB * (h / 2 - 3.dp.toPx())).toFloat().coerceIn(0f, h)
            if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line.copy(alpha = if (profile.enabled) 1f else 0.4f), style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
    }
}
