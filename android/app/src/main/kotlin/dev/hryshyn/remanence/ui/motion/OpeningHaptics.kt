package dev.hryshyn.remanence.ui.motion

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Tactile beats of the capsule opening: the card lifting off the table, the
 * swell of the turn, the edge passing, and the capsule settling into place.
 *
 * Rich haptic primitives where the device has them (API 30/31+), simpler
 * waveforms or predefined clicks otherwise; every call is best-effort and
 * silent on devices without a vibrator. Touch usage keeps the system "touch
 * feedback" setting in charge.
 */
internal class OpeningHaptics(context: Context) {
    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()?.takeIf { it.hasVibrator() }

    /** Card lets go of the table. */
    fun lift() = play(
        primitives = listOf(Primitive(PRIMITIVE_LOW_TICK, 0.6f), Primitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.3f, 40)),
        fallbackMs = longArrayOf(0, 12),
        fallbackAmplitude = intArrayOf(0, 70),
    )

    /** The turn starts to build. */
    fun turn() = play(
        primitives = listOf(Primitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, 0.35f)),
        fallbackMs = longArrayOf(0, 80, 80),
        fallbackAmplitude = intArrayOf(0, 25, 55),
    )

    /** The card passes edge-on: its back, the capsule, comes into view. */
    fun edge() = play(
        primitives = listOf(
            Primitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.45f),
            Primitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.75f, 10),
        ),
        fallbackMs = longArrayOf(0, 40, 10, 14),
        fallbackAmplitude = intArrayOf(0, 50, 0, 150),
    )

    /** The capsule lands and settles. */
    fun land() = play(
        primitives = listOf(
            Primitive(PRIMITIVE_THUD, 0.85f),
            Primitive(PRIMITIVE_LOW_TICK, 0.35f, 140),
            Primitive(PRIMITIVE_LOW_TICK, 0.2f, 160),
        ),
        fallbackMs = longArrayOf(0, 28, 150, 10, 170, 8),
        fallbackAmplitude = intArrayOf(0, 200, 0, 60, 0, 35),
    )

    private class Primitive(val id: Int, val scale: Float, val delayMs: Int = 0)

    private fun play(primitives: List<Primitive>, fallbackMs: LongArray, fallbackAmplitude: IntArray) {
        val vibrator = vibrator ?: return
        runCatching {
            val effect = composition(vibrator, primitives) ?: waveform(vibrator, fallbackMs, fallbackAmplitude)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(
                    effect,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build(),
                )
            }
        }
    }

    private fun composition(vibrator: Vibrator, primitives: List<Primitive>): VibrationEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        // Primitives newer than the device are swapped for a supported tick.
        val usable = primitives.map { p ->
            if (p.id == PRIMITIVE_THUD || p.id == PRIMITIVE_LOW_TICK) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) p else Primitive(VibrationEffect.Composition.PRIMITIVE_CLICK, p.scale * 0.6f, p.delayMs)
            } else {
                p
            }
        }
        if (!vibrator.areAllPrimitivesSupported(*usable.map { it.id }.toIntArray())) return null
        val builder = VibrationEffect.startComposition()
        usable.forEach { builder.addPrimitive(it.id, it.scale.coerceIn(0f, 1f), it.delayMs) }
        return builder.compose()
    }

    private fun waveform(vibrator: Vibrator, timings: LongArray, amplitudes: IntArray): VibrationEffect =
        if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(timings, amplitudes, -1)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        } else {
            VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE)
        }

    private companion object {
        // API 31 primitive ids, referenced by value so older SDK stubs compile.
        const val PRIMITIVE_THUD = 2
        const val PRIMITIVE_LOW_TICK = 8
    }
}

@Composable
internal fun rememberOpeningHaptics(): OpeningHaptics {
    val context = LocalContext.current
    return remember(context) { OpeningHaptics(context.applicationContext) }
}
