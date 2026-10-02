package dev.schlubbe.musicagent.playback.eq

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * Integrated loudness after ITU-R BS.1770-4 / EBU R128 (the measure Spotify,
 * YouTube and ReplayGain 2 normalise to): K-weighting, 400 ms blocks with 75 %
 * overlap, absolute gate at -70 LUFS and relative gate 10 LU below the
 * ungated mean. K-weighting coefficients for any sample rate as in libebur128.
 */
class LoudnessMeter(private val fs: Double) {
    // stage 1: high shelf (head acoustics), stage 2: RLB high-pass
    private val s1: DoubleArray
    private val s2: DoubleArray
    init {
        var k = tan(PI * 1681.974450955533 / fs)
        val q1 = 0.7071752369554196
        val vh = 10.0.pow(3.999843853973347 / 20)
        val vb = vh.pow(0.4996667741545416)
        var a0 = 1 + k / q1 + k * k
        s1 = doubleArrayOf((vh + vb * k / q1 + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q1 + k * k) / a0, 2 * (k * k - 1) / a0, (1 - k / q1 + k * k) / a0)
        k = tan(PI * 38.13547087602444 / fs)
        val q2 = 0.5003270373238773
        a0 = 1 + k / q2 + k * k
        s2 = doubleArrayOf(1.0, -2.0, 1.0, 2 * (k * k - 1) / a0, (1 - k / q2 + k * k) / a0)
    }

    /** Stage coefficients (b0, b1, b2, a1, a2) for tests. */
    internal val stage1: DoubleArray get() = s1
    internal val stage2: DoubleArray get() = s2

    private val st = Array(2) { DoubleArray(8) } // per channel: x1,x2,y1,y2 for each stage
    private val sub = (fs * 0.1).toInt()          // 100 ms sub-block = block step
    private var subSum = 0.0
    private var subCount = 0
    private val last4 = DoubleArray(4)            // the last four sub-block energies form one 400 ms block
    private var subs = 0L
    private var blocks = DoubleArray(1024)        // gated block energies (mean square, channel sum)
    private var nBlocks = 0

    /** Seconds of audio measured since [reset]. */
    val seconds: Double get() = subs * 0.1

    fun reset() {
        st.forEach { it.fill(0.0) }
        subSum = 0.0; subCount = 0; subs = 0; nBlocks = 0; last4.fill(0.0)
    }

    /** Adds interleaved stereo [buf] of [frames] frames. */
    fun add(buf: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            val l = kWeight(st[0], buf[2 * i].toDouble())
            val r = kWeight(st[1], buf[2 * i + 1].toDouble())
            subSum += l * l + r * r
            if (++subCount == sub) closeSubBlock()
        }
    }

    private fun closeSubBlock() {
        last4[(subs % 4).toInt()] = subSum / sub
        subSum = 0.0; subCount = 0
        subs++
        if (subs < 4) return
        val e = last4.sum() / 4
        if (lufs(e) <= -70.0) return
        if (nBlocks == blocks.size) blocks = blocks.copyOf(nBlocks * 2)
        blocks[nBlocks++] = e
    }

    private fun kWeight(s: DoubleArray, x: Double): Double {
        val y1 = s1[0] * x + s1[1] * s[0] + s1[2] * s[1] - s1[3] * s[2] - s1[4] * s[3]
        s[1] = s[0]; s[0] = x; s[3] = s[2]; s[2] = if (kotlin.math.abs(y1) < 1e-25) 0.0 else y1
        val y2 = s2[0] * y1 + s2[1] * s[4] + s2[2] * s[5] - s2[3] * s[6] - s2[4] * s[7]
        s[5] = s[4]; s[4] = y1; s[7] = s[6]; s[6] = if (kotlin.math.abs(y2) < 1e-25) 0.0 else y2
        return y2
    }

    /** Gated integrated loudness in LUFS, or null while nothing above -70 LUFS was measured. */
    fun integrated(): Double? {
        if (nBlocks == 0) return null
        var sum = 0.0
        for (i in 0 until nBlocks) sum += blocks[i]
        val gate = lufs(sum / nBlocks) - 10
        var gSum = 0.0; var gN = 0
        for (i in 0 until nBlocks) if (lufs(blocks[i]) > gate) { gSum += blocks[i]; gN++ }
        return if (gN == 0) null else lufs(gSum / gN)
    }

    companion object {
        fun lufs(meanSquare: Double): Double = -0.691 + 10 * log10(meanSquare.coerceAtLeast(1e-20))
    }
}

/**
 * Track loudness normalisation ("Lautstärke angleichen"). Applies one gain per
 * track so every track plays at [targetLufs]: the stored loudness of a track
 * that was played before (exact from the first sample), otherwise the running
 * BS.1770 measurement of the current track, starting from the previous track's
 * gain and gliding slowly so the change is not heard. Boost is capped at
 * [maxBoostDb]; the limiter after the EQ catches any peaks the boost causes.
 *
 * [startTrack] may be called from any thread; it takes effect with the next block.
 */
class LoudnessNormalizer(fs: Double) {
    @Volatile var enabled = false
    @Volatile var targetLufs = DEFAULT_TARGET_LUFS
    @Volatile var maxBoostDb = DEFAULT_MAX_BOOST_DB

    private val meter = LoudnessMeter(fs)
    private val pending = AtomicReference<Pair<String, Double?>?>(null)
    private var known: Double? = null
    private var gainDb = 0.0
    private var linDb = 0.0
    private var lin = 1f
    private val slewPerSample = SLEW_DB_PER_S / fs
    private val fastSlewPerSample = 12.0 / 0.05 / fs // a known value is reached within ~50 ms

    /** Id of the track being measured. */
    var trackId: String? = null
        private set

    /** A new track starts; [knownLufs] is its stored loudness, if any. */
    fun startTrack(id: String, knownLufs: Double?) = pending.set(id to knownLufs)

    /** Loudness measured for the current track so far, once enough was heard to trust it. */
    fun measuredLufs(): Double? = if (meter.seconds >= MIN_SECONDS_TO_STORE) meter.integrated() else null

    /** Current applied gain in dB (for tests and display). */
    val currentGainDb: Double get() = gainDb

    fun process(buf: FloatArray, frames: Int) {
        pending.getAndSet(null)?.let { (id, k) -> trackId = id; known = k; meter.reset() }
        meter.add(buf, frames)
        val loudness = known ?: meter.integrated()?.takeIf { meter.seconds >= MIN_SECONDS_TO_ADAPT }
        val target = when {
            !enabled -> 0.0
            loudness == null -> gainDb // keep the previous track's gain until measured
            else -> (targetLufs - loudness).coerceIn(-MAX_CUT_DB, maxBoostDb)
        }
        val step = if (known != null && enabled) fastSlewPerSample else slewPerSample
        for (i in 0 until frames) {
            if (gainDb != target) gainDb = if (gainDb < target) minOf(target, gainDb + step) else maxOf(target, gainDb - step)
            if (gainDb == 0.0) continue
            if (gainDb != linDb) { linDb = gainDb; lin = 10.0.pow(gainDb / 20).toFloat() }
            buf[2 * i] *= lin; buf[2 * i + 1] *= lin
        }
    }

    companion object {
        const val DEFAULT_TARGET_LUFS = -10.0
        const val DEFAULT_MAX_BOOST_DB = 8.0
        const val MAX_CUT_DB = 15.0
        const val SLEW_DB_PER_S = 2.0
        const val MIN_SECONDS_TO_ADAPT = 3.0
        const val MIN_SECONDS_TO_STORE = 20.0
    }
}

/**
 * Measured loudness per track id ("source:sourceId"), kept in a small JSON file so a
 * track that was heard once is normalised exactly from its first sample next time.
 * Thread-safe; [save] writes only when something changed.
 */
class LoudnessCache(private val file: File) {
    private val map: LinkedHashMap<String, Float> = object : LinkedHashMap<String, Float>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>) = size > MAX_ENTRIES
    }
    @Volatile private var dirty = false

    init {
        runCatching {
            if (file.isFile) {
                val loaded: Map<String, Float>? = Gson().fromJson(file.readText(), object : TypeToken<Map<String, Float>>() {}.type)
                synchronized(map) { loaded?.forEach { (k, v) -> if (v.isFinite()) map[k] = v } }
            }
        }
    }

    operator fun get(id: String): Double? = synchronized(map) { map[id]?.toDouble() }

    fun put(id: String, lufs: Double) {
        if (!lufs.isFinite()) return
        synchronized(map) { map[id] = lufs.toFloat() }
        dirty = true
    }

    fun save() {
        if (!dirty) return
        dirty = false
        val json = synchronized(map) { Gson().toJson(HashMap(map)) }
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) { file.writeText(json); tmp.delete() }
        }
    }

    private companion object {
        const val MAX_ENTRIES = 5000
    }
}
