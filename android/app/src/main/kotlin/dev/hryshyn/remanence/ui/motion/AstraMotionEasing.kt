package dev.hryshyn.remanence.ui.motion

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Compose easings built from the [AstraMotionSpec] control points.
 * Kept separate from the spec so the spec itself stays dependency-free
 * and unit-testable on plain JVM.
 */
val AstraGentleEasing: Easing = CubicBezierEasing(
    AstraMotionSpec.EASING_GENTLE[0],
    AstraMotionSpec.EASING_GENTLE[1],
    AstraMotionSpec.EASING_GENTLE[2],
    AstraMotionSpec.EASING_GENTLE[3],
)

/** Continuous-Carry travel easing. */
val AstraTravelEasing: Easing = CubicBezierEasing(
    AstraMotionSpec.EASING_TRAVEL[0],
    AstraMotionSpec.EASING_TRAVEL[1],
    AstraMotionSpec.EASING_TRAVEL[2],
    AstraMotionSpec.EASING_TRAVEL[3],
)

/** Continuous-Carry dissolve easing. */
val AstraDissolveEasing: Easing = CubicBezierEasing(
    AstraMotionSpec.EASING_DISSOLVE[0],
    AstraMotionSpec.EASING_DISSOLVE[1],
    AstraMotionSpec.EASING_DISSOLVE[2],
    AstraMotionSpec.EASING_DISSOLVE[3],
)

/**
 * Tween with the authored duration, or an instant snap when the resolved
 * duration is 0 — so animator-duration-scale 0 still lands on the end
 * state instead of freezing mid-transition.
 */
fun <T> astraTween(durationMs: Int, easing: Easing): FiniteAnimationSpec<T> =
    if (durationMs <= 0) snap() else tween(durationMs, easing = easing)

private fun readAnimatorScale(context: Context): Float = try {
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
} catch (_: Exception) {
    1f
}

/**
 * Resolves the current motion regime from the system animator-duration
 * scale (`Settings.Global.ANIMATOR_DURATION_SCALE`) and keeps it live:
 * a content observer re-resolves whenever the setting changes, so
 * toggling animations / reduced motion applies without recomposing from
 * scratch or restarting the flow. The platform "Remove animations"
 * accessibility setting drives the scale to 0, which resolves to
 * [AstraMotionSpec.Resolved.INSTANT].
 *
 * Never throws in composition: an unreadable setting falls back to full
 * motion rather than crashing the surface.
 */
@Composable
fun rememberAstraMotion(): AstraMotionSpec.Resolved {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(readAnimatorScale(context)) }
    DisposableEffect(context) {
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scale = readAnimatorScale(context)
            }
        }
        context.contentResolver.registerContentObserver(uri, false, observer)
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }
    return AstraMotionSpec.resolve(scale)
}
