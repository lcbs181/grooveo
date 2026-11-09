package dev.schlubbe.musicagent.playback.eq

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
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
 * gain and gliding slowly so the change is not heard.
 *
 * Like Spotify's and YouTube's normalisation, a quiet track is only raised as far as its
 * own peaks allow: the boost never lifts a peak above [PEAK_CEILING_DB] (the track's stored
 * peak, or the highest sample heard so far), and at most [maxBoostDb]. So the limiter after
 * the EQ has nothing to do for normalisation; raising a quiet but dynamic track into it made
 * it compress all the time. With the limiter switched off ([allowBoost] false) nothing is
 * boosted at all: tracks are only turned down.
 *
 * [startTrack] may be called from any thread; it takes effect with the next block.
 */
class LoudnessNormalizer(fs: Double) {
    @Volatile var enabled = false
    @Volatile var targetLufs = DEFAULT_TARGET_LUFS
    @Volatile var maxBoostDb = DEFAULT_MAX_BOOST_DB
    /** False while the limiter is off: no gain above 0 dB, since nothing would catch the peaks. */
    @Volatile var allowBoost = true

    private val meter = LoudnessMeter(fs)
    private val pending = AtomicReference<Triple<String, Double?, Double?>?>(null)
    private var known: Double? = null
    /** Highest sample of the current track, linear: the stored peak, raised by what is heard. */
    private var peak = 0.0
    private var boostCapDb = Double.POSITIVE_INFINITY
    private var latched: Double? = null // measurement frozen once the track was heard long enough: no drifting level mid-song
    private var gainDb = 0.0
    private var linDb = 0.0
    private var lin = 1f
    private val slewPerSample = SLEW_DB_PER_S / fs
    private val fastSlewPerSample = 12.0 / 0.05 / fs // a known value is reached within ~50 ms

    /** Id of the track being measured. */
    var trackId: String? = null
        private set

    /** A new track starts; [knownLufs] is its stored loudness and [knownPeakDb] its sample peak (dBFS), if any. */
    fun startTrack(id: String, knownLufs: Double?, knownPeakDb: Double? = null) = pending.set(Triple(id, knownLufs, knownPeakDb))

    /** Loudness measured for the current track so far, once enough was heard to trust it. */
    fun measuredLufs(): Double? = if (meter.seconds >= MIN_SECONDS_TO_STORE) meter.integrated() else null

    /** Highest sample of the current track so far in dBFS (before the gain), once enough was heard to store it. */
    fun measuredPeakDb(): Double? = if (meter.seconds >= MIN_SECONDS_TO_STORE && peak > 0) 20 * log10(peak) else null

    private fun raisePeak(p: Double) {
        peak = p
        boostCapDb = maxOf(0.0, PEAK_CEILING_DB - 20 * log10(p))
    }

    /** Current applied gain in dB (for tests, display and the limiter). */
    val currentGainDb: Double get() = gainDb

    fun process(buf: FloatArray, frames: Int) {
        pending.getAndSet(null)?.let { (id, k, kp) ->
            trackId = id; known = k; latched = null; meter.reset()
            peak = 0.0; boostCapDb = Double.POSITIVE_INFINITY
            kp?.takeIf { it.isFinite() }?.let { raisePeak(10.0.pow(it / 20)) }
            // a new track starts at a boundary: drop a leftover boost right there, not over seconds into it
            if (k == null) gainDb = minOf(gainDb, 0.0)
        }
        meter.add(buf, frames)
        if (latched == null && known == null && meter.seconds >= MIN_SECONDS_TO_STORE) latched = meter.integrated()
        val loudness = known ?: latched ?: meter.integrated()?.takeIf { meter.seconds >= MIN_SECONDS_TO_ADAPT }
        val target = when {
            !enabled -> 0.0
            // until measured: the previous track's cut, but never its boost (a quiet track's
            // +6 dB on a loud intro would blast)
            loudness == null -> minOf(gainDb, 0.0)
            else -> (targetLufs - loudness).coerceIn(-MAX_CUT_DB, if (allowBoost) maxBoostDb else 0.0).coerceAtMost(maxOf(0.0, boostCapDb))
        }
        // a known value is reached at once; while a new track is still being measured it may move faster
        val step = when {
            known != null && enabled -> fastSlewPerSample
            latched == null -> slewPerSample * 2
            else -> slewPerSample
        }
        for (i in 0 until frames) {
            val a = maxOf(abs(buf[2 * i]), abs(buf[2 * i + 1])).toDouble()
            if (a > peak) {
                raisePeak(a)
                // a louder peak than known so far: take back at once the part of the boost it no longer allows
                if (gainDb > boostCapDb) gainDb = boostCapDb
            }
            val capped = minOf(target, if (target > 0.0) boostCapDb else target)
            if (gainDb != capped) gainDb = if (gainDb < capped) minOf(capped, gainDb + step) else maxOf(capped, gainDb - step)
            val db = gainDb
            if (db == 0.0) continue
            if (db != linDb) { linDb = db; lin = 10.0.pow(db / 20).toFloat() }
            buf[2 * i] *= lin; buf[2 * i + 1] *= lin
        }
    }

    companion object {
        // -14 LUFS as on Spotify and YouTube; -12 and -10 raised quiet tracks into the limiter
        const val DEFAULT_TARGET_LUFS = -14.0
        /** A boost never lifts the track's peak above this (dBFS), leaving room for decoder overshoot. */
        const val PEAK_CEILING_DB = -1.0
        const val DEFAULT_MAX_BOOST_DB = 6.0
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

    /** The track's sample peak in dBFS, if stored (kept under its own key, so older files still read). */
    fun peak(id: String): Double? = synchronized(map) { map[PEAK + id]?.toDouble() }

    fun put(id: String, lufs: Double, peakDb: Double? = null) {
        if (!lufs.isFinite()) return
        synchronized(map) {
            map[id] = lufs.toFloat()
            if (peakDb != null && peakDb.isFinite()) map[PEAK + id] = peakDb.toFloat()
        }
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
        const val MAX_ENTRIES = 10000 // loudness and peak: two per track
        const val PEAK = "peak:"
    }
}
