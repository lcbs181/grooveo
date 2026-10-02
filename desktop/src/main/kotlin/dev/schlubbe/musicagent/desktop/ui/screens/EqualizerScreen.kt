package dev.schlubbe.musicagent.desktop.ui.screens

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowCounterClockwise
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.Export
import com.adamglin.phosphoricons.regular.FloppyDisk
import com.adamglin.phosphoricons.regular.MagicWand
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.Trash
import com.adamglin.phosphoricons.regular.UploadSimple
import dev.schlubbe.musicagent.playback.eq.EqBand
import dev.schlubbe.musicagent.playback.eq.EqProcessor
import dev.schlubbe.musicagent.playback.eq.EqProfile
import dev.schlubbe.musicagent.playback.eq.FilterType
import dev.schlubbe.musicagent.desktop.audio.Sound3dPreset
import dev.schlubbe.musicagent.desktop.audio.SpectrumAnalyzer
import dev.schlubbe.musicagent.desktop.ui.C
import dev.schlubbe.musicagent.desktop.ui.IconBtn
import dev.schlubbe.musicagent.desktop.ui.LocalUi
import dev.schlubbe.musicagent.desktop.ui.Panel
import dev.schlubbe.musicagent.desktop.ui.Pill
import dev.schlubbe.musicagent.desktop.ui.PrimaryButton
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** Pure mapping between the response graph and EQ values (unit-tested). */
object EqGeometry {
    const val F_MIN = 20.0
    const val F_MAX = 20000.0
    const val DB_RANGE = 18.0

    fun freqToX(f: Double, width: Float): Float = (ln(f / F_MIN) / ln(F_MAX / F_MIN) * width).toFloat()
    fun xToFreq(x: Float, width: Float): Double = F_MIN * (F_MAX / F_MIN).pow((x / width).toDouble().coerceIn(0.0, 1.0))
    fun dbToY(db: Double, height: Float): Float = ((DB_RANGE - db) / (2 * DB_RANGE) * height).toFloat()
    fun yToDb(y: Float, height: Float): Double = (DB_RANGE - y / height * 2 * DB_RANGE).coerceIn(-EqBand.MAX_GAIN, EqBand.MAX_GAIN)

    /** Snaps to musically useful precision: 3 significant digits for Hz, 0.1 dB, 0.01 Q. */
    fun roundFreq(f: Double): Double {
        val mag = 10.0.pow(kotlin.math.floor(kotlin.math.log10(f)) - 2)
        return (f / mag).roundToInt() * mag
    }
    fun roundDb(db: Double) = (db * 10).roundToInt() / 10.0

    fun formatFreq(f: Double): String = if (f >= 1000) String.format(Locale.GERMAN, "%.1f kHz", f / 1000).replace(",0 ", " ") else "${f.roundToInt()} Hz"

    /** Index of the band whose node is within [radius] px of [p], else null. */
    fun hitBand(profile: EqProfile, p: Offset, width: Float, height: Float, radius: Float): Int? =
        profile.bands.indices.minByOrNull { i -> nodePos(profile, i, width, height).minus(p).getDistance() }
            ?.takeIf { nodePos(profile, it, width, height).minus(p).getDistance() <= radius }

    fun nodePos(profile: EqProfile, i: Int, width: Float, height: Float): Offset {
        val b = profile.bands[i]
        return Offset(freqToX(b.freq, width), dbToY(if (b.type.hasGain) b.gainDb else 0.0, height))
    }
}

private val BAND_COLORS = listOf(
    Color(0xFFFF7A5C), Color(0xFF6FB49A), Color(0xFFF2C14E), Color(0xFF7FA7FF), Color(0xFFD48BFF),
    Color(0xFF4FD1C5), Color(0xFFFF9FB2), Color(0xFFB5E36B),
)

private fun bandColor(i: Int) = BAND_COLORS[i % BAND_COLORS.size]

/**
 * Parametric equalizer. The bands run as analog-matched biquads in EqProcessor; this screen edits the [EqProfile] stored in settings (applied live).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun EqualizerScreen() {
    val ui = LocalUi.current
    val g = ui.graph
    val c = C.c
    val settings by g.settings.state.collectAsState()
    val eq = settings.eq
    var selected by remember { mutableIntStateOf(0) }
    fun set(p: EqProfile) = g.settings.update { it.copy(eq = p) }
    fun setBand(i: Int, f: (EqBand) -> EqBand) =
        set(eq.copy(name = "Eigen", bands = eq.bands.mapIndexed { j, b -> if (j == i) f(b).clamped() else b }))

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Equalizer", style = MaterialTheme.typography.displayMedium, color = c.text)
                Text("Parametrisch · ${eq.bands.size} Bänder · analog-getreue Filter (Vicanek) · 64-Bit", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            }
            Text(if (eq.enabled) "Aktiv" else "Umgangen", style = MaterialTheme.typography.labelLarge, color = if (eq.enabled) c.accent else c.textFaint)
            Spacer(Modifier.width(10.dp))
            Switch(eq.enabled, { set(eq.copy(enabled = it)) }, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
        }

        Spacer(Modifier.height(18.dp))
        Text("VOREINSTELLUNGEN", style = MaterialTheme.typography.labelSmall, color = c.textFaint)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EqProfile.PRESETS.forEach { p -> Pill(p.name, eq.name == p.name) { set(p.copy(enabled = true)); selected = 0 } }
            settings.eqUserPresets.forEach { p ->
                ContextMenuArea(items = { listOf(ContextMenuItem("Vorlage löschen") { g.settings.update { s -> s.copy(eqUserPresets = s.eqUserPresets - p) } }) }) {
                    Pill("★ ${p.name}", eq.name == p.name) { set(p.copy(enabled = true)); selected = 0 }
                }
            }
            if (eq.name == "Eigen") Pill("Eigen", true) {}
        }

        Spacer(Modifier.height(16.dp))
        Panel(Modifier.fillMaxWidth()) {
            Column {
                ResponseGraph(eq, selected, onSelect = { selected = it }, onChange = { set(it) })
                Spacer(Modifier.height(10.dp))
                Text(
                    "Knoten ziehen: Frequenz & Verstärkung · Mausrad: Güte (Q) · Doppelklick: Band hinzufügen · Rechtsklick: Bandmenü",
                    style = MaterialTheme.typography.bodySmall, color = c.textFaint,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Panel(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bänder", style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.weight(1f))
                    IconBtn(PhosphorIcons.Regular.Plus, "Band hinzufügen", enabled = eq.bands.size < EqProfile.MAX_BANDS, tint = c.accent) {
                        set(eq.copy(name = "Eigen", bands = eq.bands + EqBand(FilterType.PEAK, 1000.0, 0.0, 1.0)))
                        selected = eq.bands.size
                    }
                }
                BandHeader()
                eq.bands.forEachIndexed { i, b ->
                    BandRow(i, b, i == selected, onSelect = { selected = i }, onChange = { f -> setBand(i, f) }, onRemove = {
                        set(eq.copy(name = "Eigen", bands = eq.bands.filterIndexed { j, _ -> j != i }))
                        selected = selected.coerceAtMost(eq.bands.size - 2).coerceAtLeast(0)
                    })
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel(Modifier.weight(1f)) { PreampSection(eq, ::set) }
            Panel(Modifier.weight(1f)) { ProfileActions(eq, ::set) }
        }

        Spacer(Modifier.height(16.dp))
        Panel(Modifier.fillMaxWidth()) { BassSection(eq, ::set) }

        Spacer(Modifier.height(16.dp))
        Panel(Modifier.fillMaxWidth()) {
            Column {
                Text("3D-Sound", style = MaterialTheme.typography.titleLarge, color = c.text)
                Text("Raumklang-Vorlage · Faltungshall mit echten Raumimpulsantworten", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Sound3dPreset.entries.forEach { p -> Pill(p.label, settings.sound3dPreset == p.name) { g.settings.update { it.copy(sound3dPreset = p.name) } } }
                }
                Spacer(Modifier.height(8.dp))
                Text(Sound3dPreset.of(settings.sound3dPreset).description, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ResponseGraph(eq: EqProfile, selected: Int, onSelect: (Int) -> Unit, onChange: (EqProfile) -> Unit) {
    val g = LocalUi.current.graph
    val c = C.c
    val measurer = rememberTextMeasurer()
    var spectrum by remember { mutableStateOf(FloatArray(SpectrumAnalyzer.BANDS)) }
    val engine by g.player.engineState.collectAsState()
    LaunchedEffect(engine.playing) {
        while (engine.playing) withFrameNanos { spectrum = g.player.spectrum.latest.bands }
    }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var menuFor by remember { mutableStateOf<Int?>(null) }
    var latest by remember { mutableStateOf(eq) }
    latest = eq
    val labelStyle = TextStyle(fontSize = 10.sp, color = c.textFaint)
    Box {
        androidx.compose.foundation.Canvas(
            Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(12.dp)).background(c.bg.copy(alpha = 0.6f))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { p -> EqGeometry.hitBand(latest, p, size.width.toFloat(), size.height.toFloat(), 18f)?.let(onSelect) },
                        onDoubleTap = { p ->
                            val cur = latest
                            if (cur.bands.size < EqProfile.MAX_BANDS && EqGeometry.hitBand(cur, p, size.width.toFloat(), size.height.toFloat(), 18f) == null) {
                                val nb = EqBand(FilterType.PEAK, EqGeometry.roundFreq(EqGeometry.xToFreq(p.x, size.width.toFloat())),
                                    EqGeometry.roundDb(EqGeometry.yToDb(p.y, size.height.toFloat())), 1.0)
                                onChange(cur.copy(name = "Eigen", bands = cur.bands + nb))
                                onSelect(cur.bands.size)
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { p -> dragging = EqGeometry.hitBand(latest, p, size.width.toFloat(), size.height.toFloat(), 22f)?.also(onSelect) },
                        onDragEnd = { dragging = null },
                        onDragCancel = { dragging = null },
                    ) { change, _ ->
                        val i = dragging ?: return@detectDragGestures
                        val cur = latest
                        val p = change.position
                        val b = cur.bands[i]
                        val nb = b.copy(
                            freq = EqGeometry.roundFreq(EqGeometry.xToFreq(p.x, size.width.toFloat())),
                            gainDb = if (b.type.hasGain) EqGeometry.roundDb(EqGeometry.yToDb(p.y, size.height.toFloat())) else b.gainDb,
                        ).clamped()
                        onChange(cur.copy(name = "Eigen", bands = cur.bands.mapIndexed { j, x -> if (j == i) nb else x }))
                    }
                }
                .onPointerEvent(PointerEventType.Scroll) { ev ->
                    val cur = latest
                    val i = cur.bands.indices.firstOrNull { it == selected } ?: return@onPointerEvent
                    val dy = ev.changes.first().scrollDelta.y
                    val b = cur.bands[i]
                    val nq = ((b.q * if (dy > 0) 0.9 else 1.0 / 0.9) * 100).roundToInt() / 100.0
                    onChange(cur.copy(name = "Eigen", bands = cur.bands.mapIndexed { j, x -> if (j == i) x.copy(q = nq).clamped() else x }))
                }
                .onPointerEvent(PointerEventType.Press) { ev ->
                    if (ev.buttons.isSecondaryPressed) {
                        val p = ev.changes.first().position
                        EqGeometry.hitBand(latest, p, size.width.toFloat(), size.height.toFloat(), 18f)?.let { onSelect(it); menuFor = it }
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            // grid
            listOf(20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0).forEach { f ->
                val x = EqGeometry.freqToX(f, w)
                drawLine(c.divider, Offset(x, 0f), Offset(x, h))
                val label = if (f >= 1000) "${(f / 1000).toInt()}k" else "${f.toInt()}"
                drawText(measurer, label, Offset((x + 3).coerceAtMost(w - 22), h - 14), labelStyle)
            }
            for (db in listOf(-12, -6, 0, 6, 12)) {
                val y = EqGeometry.dbToY(db.toDouble(), h)
                drawLine(if (db == 0) c.textFaint.copy(alpha = 0.5f) else c.divider, Offset(0f, y), Offset(w, y))
                drawText(measurer, "${if (db > 0) "+" else ""}$db dB", Offset(4f, y - 13), labelStyle)
            }
            // live spectrum of what is playing
            val sp = Path().apply {
                moveTo(0f, h)
                spectrum.forEachIndexed { i, v ->
                    val f = 35.0 * (16000.0 / 35.0).pow(i / SpectrumAnalyzer.BANDS.toDouble())
                    lineTo(EqGeometry.freqToX(f, w), h - v * h * 0.85f)
                }
                lineTo(w, h); close()
            }
            drawPath(sp, Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.28f), c.accent.copy(alpha = 0.04f))))
            // individual bands
            eq.bands.forEachIndexed { i, b ->
                if (!b.enabled) return@forEachIndexed
                val path = Path()
                for (x in 0..w.toInt() step 4) {
                    val y = EqGeometry.dbToY(b.responseDb(EqGeometry.xToFreq(x.toFloat(), w)).coerceIn(-30.0, 30.0), h)
                    if (x == 0) path.moveTo(0f, y) else path.lineTo(x.toFloat(), y)
                }
                drawPath(path, bandColor(i).copy(alpha = if (i == selected) 0.7f else 0.25f), style = Stroke(1.5f))
            }
            // total response
            val total = Path()
            val zero = EqGeometry.dbToY(0.0, h)
            val fill = Path().apply { moveTo(0f, zero) }
            for (x in 0..w.toInt() step 2) {
                // shape of the EQ (the preamp only shifts the level; it is shown in its own section)
                val y = EqGeometry.dbToY((eq.responseDb(EqGeometry.xToFreq(x.toFloat(), w)) - if (eq.enabled) eq.preampDb else 0.0).coerceIn(-30.0, 30.0), h)
                if (x == 0) total.moveTo(0f, y) else total.lineTo(x.toFloat(), y)
                fill.lineTo(x.toFloat(), y)
            }
            fill.lineTo(w, zero); fill.close()
            val active = if (eq.enabled) c.accent2 else c.textFaint
            drawPath(fill, active.copy(alpha = 0.12f))
            drawPath(total, active, style = Stroke(3f))
            // nodes
            eq.bands.forEachIndexed { i, b ->
                val p = EqGeometry.nodePos(eq, i, w, h)
                val col = if (b.enabled) bandColor(i) else c.textFaint
                if (i == selected) drawCircle(col.copy(alpha = 0.25f), 18f, p)
                drawCircle(col, 9f, p)
                drawCircle(c.surface, 9f, p, style = Stroke(2f))
                drawText(measurer, "${i + 1}", p + Offset(-3.5f, -24f), TextStyle(fontSize = 10.sp, color = col, fontWeight = FontWeight.Bold))
            }
        }
        val mi = menuFor
        DropdownMenu(mi != null, onDismissRequest = { menuFor = null }) {
            if (mi != null && mi in eq.bands.indices) {
                FilterType.entries.forEach { t ->
                    DropdownMenuItem(text = { Text("Typ: ${t.label}") }, onClick = {
                        onChange(eq.copy(name = "Eigen", bands = eq.bands.mapIndexed { j, x -> if (j == mi) x.copy(type = t) else x })); menuFor = null
                    })
                }
                DropdownMenuItem(text = { Text(if (eq.bands[mi].enabled) "Deaktivieren" else "Aktivieren") }, onClick = {
                    onChange(eq.copy(name = "Eigen", bands = eq.bands.mapIndexed { j, x -> if (j == mi) x.copy(enabled = !x.enabled) else x })); menuFor = null
                })
                DropdownMenuItem(text = { Text("Entfernen") }, onClick = {
                    onChange(eq.copy(name = "Eigen", bands = eq.bands.filterIndexed { j, _ -> j != mi })); menuFor = null
                })
            }
        }
    }
}

@Composable
private fun BandHeader() {
    val c = C.c
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("" to 28, "An" to 52, "Typ" to 128, "Frequenz" to 0, "Verstärkung" to 0, "Güte (Q)" to 96, "" to 36).forEach { (t, w) ->
            Text(t, style = MaterialTheme.typography.labelSmall, color = c.textFaint, modifier = if (w == 0) Modifier.weight(1f) else Modifier.width(w.dp))
        }
    }
}

@Composable
private fun BandRow(i: Int, b: EqBand, selected: Boolean, onSelect: () -> Unit, onChange: ((EqBand) -> EqBand) -> Unit, onRemove: () -> Unit) {
    val c = C.c
    var typeMenu by remember { mutableStateOf(false) }
    val sliderColors = SliderDefaults.colors(thumbColor = bandColor(i), activeTrackColor = bandColor(i), inactiveTrackColor = c.surfaceHigh)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (selected) c.surfaceHigh else Color.Transparent)
            .clickable(onClick = onSelect).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(28.dp)) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(bandColor(i)), contentAlignment = Alignment.Center) {
                Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = Color.Black)
            }
        }
        Box(Modifier.width(52.dp)) { Switch(b.enabled, { v -> onChange { it.copy(enabled = v) } }, Modifier.size(width = 40.dp, height = 24.dp), colors = SwitchDefaults.colors(checkedTrackColor = c.accent)) }
        Box(Modifier.width(128.dp)) {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, c.divider, RoundedCornerShape(8.dp)).clickable { typeMenu = true }.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (b.type.hasSlope && b.slope > 1) "${b.type.label} ${b.slope * 12}" else b.type.label,
                    style = MaterialTheme.typography.labelMedium, color = c.text, modifier = Modifier.width(80.dp))
                Icon(PhosphorIcons.Regular.CaretDown, null, tint = c.textMuted, modifier = Modifier.size(12.dp))
            }
            DropdownMenu(typeMenu, { typeMenu = false }) {
                FilterType.entries.forEach { t -> DropdownMenuItem(text = { Text(t.label) }, onClick = { onChange { it.copy(type = t) }; typeMenu = false }) }
                if (b.type.hasSlope) (1..4).forEach { n ->
                    DropdownMenuItem(text = { Text("${if (b.slope == n) "● " else ""}Steilheit ${n * 12} dB/Okt.") }, onClick = { onChange { it.copy(slope = n) }; typeMenu = false })
                }
            }
        }
        // frequency: log slider + numeric field
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Slider(
                (ln(b.freq / EqGeometry.F_MIN) / ln(EqGeometry.F_MAX / EqGeometry.F_MIN)).toFloat(),
                { v -> onChange { it.copy(freq = EqGeometry.roundFreq(EqGeometry.F_MIN * (EqGeometry.F_MAX / EqGeometry.F_MIN).pow(v.toDouble()))) } },
                Modifier.weight(1f), colors = sliderColors,
            )
            NumberField(b.freq, "Hz", 72) { v -> onChange { it.copy(freq = v) } }
        }
        Row(Modifier.weight(1f).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Slider(b.gainDb.toFloat(), { v -> onChange { it.copy(gainDb = EqGeometry.roundDb(v.toDouble())) } }, Modifier.weight(1f),
                enabled = b.type.hasGain, valueRange = -EqBand.MAX_GAIN.toFloat()..EqBand.MAX_GAIN.toFloat(), colors = sliderColors)
            NumberField(b.gainDb, "dB", 64, enabled = b.type.hasGain) { v -> onChange { it.copy(gainDb = v) } }
        }
        Box(Modifier.width(96.dp).padding(start = 8.dp)) { NumberField(b.q, "", 64) { v -> onChange { it.copy(q = v) } } }
        IconBtn(PhosphorIcons.Regular.Trash, "Band entfernen", size = 32.dp, iconSize = 16.dp, onClick = onRemove)
    }
}

/** Compact numeric input; commits on Enter or focus loss, accepts "," or ".". */
@Composable
private fun NumberField(value: Double, unit: String, widthDp: Int, enabled: Boolean = true, onCommit: (Double) -> Unit) {
    val c = C.c
    val shown = if (value >= 100) value.roundToInt().toString() else String.format(Locale.GERMAN, "%.2f", value).trimEnd('0').trimEnd(',')
    var text by remember(value) { mutableStateOf(shown) }
    fun commit() { text.replace(',', '.').toDoubleOrNull()?.let(onCommit) ?: run { text = shown } }
    Row(
        Modifier.width(widthDp.dp).clip(RoundedCornerShape(6.dp)).background(c.surfaceHigh).padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            text, { text = it }, enabled = enabled, singleLine = true,
            textStyle = MaterialTheme.typography.labelMedium.copy(color = if (enabled) c.text else c.textFaint),
            cursorBrush = SolidColor(c.accent2),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier.weight(1f).onFocusChanged { if (!it.isFocused && text != shown) commit() }
                .onPreviewKeyEventEnter { commit() },
        )
        if (unit.isNotEmpty()) Text(unit, style = MaterialTheme.typography.labelSmall, color = c.textFaint)
    }
}

private fun Modifier.onPreviewKeyEventEnter(action: () -> Unit) = this.then(
    Modifier.onPreviewKeyEvent { e ->
        if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.Enter) { action(); true } else false
    },
)

@Composable
private fun BassSection(eq: EqProfile, set: (EqProfile) -> Unit) {
    val ui = LocalUi.current
    val c = C.c
    val volume by ui.graph.settings.state.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Bass", style = MaterialTheme.typography.titleLarge, color = c.text)
        Text("Für satten, sauberen Bass: Rumpeln entfernen, Obertöne ergänzen, bei leiser Wiedergabe ausgleichen.",
            style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ToggleRow("Subsonic-Filter", "Hochpass 20 Hz, 24 dB/Okt. – unhörbares Rumpeln kostet Headroom und macht Bass matschig", eq.subsonic) { set(eq.copy(subsonic = it)) }
                ToggleRow(
                    "Loudness-Kompensation",
                    EqProcessor.loudnessGains(volume.volume).let { (b, t) -> String.format(Locale.GERMAN, "Gehörrichtig nach ISO 226 · bei aktueller Lautstärke Bass %+.1f dB, Höhen %+.1f dB", b, t) },
                    eq.loudness,
                ) { set(eq.copy(loudness = it)) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Bass-Enhancer (psychoakustisch)", style = MaterialTheme.typography.titleSmall, color = c.text)
                Text("Ergänzt Obertöne des Tiefbasses – das Ohr hört den Grundton mit, auch auf kleinen Lautsprechern.",
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Stärke", style = MaterialTheme.typography.labelMedium, color = c.textMuted, modifier = Modifier.width(70.dp))
                    Slider(eq.bassEnhance.toFloat(), { set(eq.copy(bassEnhance = (it * 100).roundToInt() / 100.0)) }, Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = c.accent2, activeTrackColor = c.accent2))
                    Text(if (eq.bassEnhance == 0.0) "Aus" else "${(eq.bassEnhance * 100).roundToInt()} %", style = MaterialTheme.typography.labelLarge,
                        color = c.text, modifier = Modifier.width(52.dp).padding(start = 8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bis", style = MaterialTheme.typography.labelMedium, color = c.textMuted, modifier = Modifier.width(70.dp))
                    Slider(eq.bassEnhanceFreq.toFloat(), { set(eq.copy(bassEnhanceFreq = it.roundToInt().toDouble())) }, Modifier.weight(1f),
                        valueRange = 40f..160f, enabled = eq.bassEnhance > 0, colors = SliderDefaults.colors(thumbColor = c.accent2, activeTrackColor = c.accent2))
                    Text("${eq.bassEnhanceFreq.roundToInt()} Hz", style = MaterialTheme.typography.labelLarge, color = c.text, modifier = Modifier.width(52.dp).padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = C.c
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.text)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
    }
}

@Composable
private fun PreampSection(eq: EqProfile, set: (EqProfile) -> Unit) {
    val c = C.c
    val headroom = eq.peakBoostDb() + eq.preampDb
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Vorverstärker", style = MaterialTheme.typography.titleLarge, color = c.text)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(eq.preampDb.toFloat(), { set(eq.copy(preampDb = EqGeometry.roundDb(it.toDouble()))) }, Modifier.weight(1f), valueRange = -24f..12f,
                colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent))
            Text(String.format(Locale.GERMAN, "%+.1f dB", eq.preampDb), style = MaterialTheme.typography.labelLarge, color = c.text, modifier = Modifier.width(72.dp).padding(start = 8.dp))
        }
        if (headroom > 0.05) Text(
            String.format(Locale.GERMAN, "Bis zu %+.1f dB über 0 dBFS – Übersteuerung möglich.", headroom),
            style = MaterialTheme.typography.bodySmall, color = c.accent2,
        )
        PrimaryButton("Automatisch anpassen", PhosphorIcons.Regular.MagicWand, accent = false) { set(eq.withAutoPreamp()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Limiter", style = MaterialTheme.typography.titleSmall, color = c.text)
                Text("Verhindert Clipping bei starken Anhebungen", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            }
            Switch(eq.limiter, { set(eq.copy(limiter = it)) }, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
        }
    }
}

@Composable
private fun ProfileActions(eq: EqProfile, set: (EqProfile) -> Unit) {
    val ui = LocalUi.current
    val g = ui.graph
    val c = C.c
    var name by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Profil", style = MaterialTheme.typography.titleLarge, color = c.text)
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                name, { name = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text), cursorBrush = SolidColor(c.accent2),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(c.surfaceHigh).padding(10.dp),
                decorationBox = { inner -> if (name.isEmpty()) Text("Name der Vorlage", color = c.textFaint, style = MaterialTheme.typography.bodyMedium); inner() },
            )
            IconBtn(PhosphorIcons.Regular.FloppyDisk, "Als Vorlage speichern", tint = c.accent) {
                val n = name.trim().ifEmpty { "Vorlage ${g.settings.current.eqUserPresets.size + 1}" }
                val p = eq.copy(name = n)
                g.settings.update { s -> s.copy(eq = p, eqUserPresets = s.eqUserPresets.filterNot { it.name == n } + p) }
                name = ""
                ui.toast("Vorlage „$n“ gespeichert")
            }
        }
        PrimaryButton("AutoEQ / Equalizer APO importieren", PhosphorIcons.Regular.UploadSimple, accent = false) {
            chooseFile("EQ-Profil importieren", save = false)?.let { f ->
                runCatching { EqProfile.parseApo(f.readText(), f.nameWithoutExtension) }
                    .onSuccess { set(it); ui.toast("${it.bands.size} Filter importiert") }
                    .onFailure { ui.toast("Import fehlgeschlagen: ${it.message}") }
            }
        }
        PrimaryButton("Als ParametricEQ.txt exportieren", PhosphorIcons.Regular.Export, accent = false) {
            chooseFile("EQ-Profil exportieren", save = true, suggested = "${eq.name}.txt")?.let { f ->
                runCatching { f.writeText(eq.toApoText()) }.onSuccess { ui.toast("Exportiert nach ${f.name}") }.onFailure { ui.toast("Export fehlgeschlagen") }
            }
        }
        PrimaryButton("Zurücksetzen", PhosphorIcons.Regular.ArrowCounterClockwise, accent = false) { set(EqProfile.flat()) }
    }
}

private fun chooseFile(title: String, save: Boolean, suggested: String? = null): File? {
    val d = FileDialog(null as Frame?, title, if (save) FileDialog.SAVE else FileDialog.LOAD)
    if (suggested != null) d.file = suggested
    d.isVisible = true
    return d.file?.let { File(d.directory, it) }
}
