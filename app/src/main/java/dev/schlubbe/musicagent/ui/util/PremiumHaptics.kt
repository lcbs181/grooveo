package dev.schlubbe.musicagent.ui.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Signature multi-pulse vibration patterns for the app's highest-value moments -
 * inspired by Duolingo's layered, textured haptics (a lesson finishing isn't one
 * generic buzz, it's a short-then-strong double tap; a mistake is a firm double
 * buzz, not a flat click) rather than Compose's default [androidx.compose.ui.hapticfeedback.HapticFeedbackType]
 * constants, most of which render as the exact same single click on real hardware
 * regardless of what actually happened.
 *
 * Every call site already treats haptics as decorative rather than required
 * feedback, so this silently no-ops on a device with no vibrator (some tablets/
 * emulators) instead of surfacing anything to the caller.
 */
class PremiumHaptics(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private fun play(timings: LongArray, amplitudes: IntArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching { v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1)) }
    }

    /** A track starting to play, a download finishing, a playlist saved - a
     * bright, satisfying short-then-strong double tap. */
    fun success() = play(longArrayOf(0, 25, 40, 45), intArrayOf(0, 120, 0, 255))

    /** Liking/hearting something - a single crisp, light "pop", quicker and
     * lighter than [success] since it happens far more often and shouldn't wear
     * out its welcome. */
    fun like() = play(longArrayOf(0, 20), intArrayOf(0, 200))

    /** Unliking / removing / toggling something off - shorter and softer than
     * [like], so "on" and "off" read as genuinely different textures rather than
     * the same buzz twice. */
    fun unlike() = play(longArrayOf(0, 15), intArrayOf(0, 110))

    /** A genuine error / rejected action - a firm double buzz, longer and
     * stronger than [success]'s taps, clearly reading as "no" rather than "yes". */
    fun error() = play(longArrayOf(0, 40, 60, 40), intArrayOf(0, 220, 0, 220))

    /** A bigger milestone - queueing a whole playlist for download, following an
     * artist, finishing onboarding - a fun three-step ascending pattern with more
     * ceremony than an everyday [success]. */
    fun celebrate() = play(longArrayOf(0, 20, 35, 25, 35, 35), intArrayOf(0, 90, 0, 160, 0, 255))

    /** A light, neutral selection tick - switching a chip/tab/segmented control or
     * the visualizer style - far lighter than [like] so routine navigation never
     * feels heavy-handed. */
    fun select() = play(longArrayOf(0, 12), intArrayOf(0, 80))
}

@Composable
fun rememberPremiumHaptics(): PremiumHaptics {
    val context = LocalContext.current
    return remember(context) { PremiumHaptics(context) }
}
