package dev.hryshyn.remanence.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Routine state-arrival wrapper for scan/result surfaces: a presence fade
 * using the authored routine token (340 ms gentle) with the 80 ms
 * crossfade under reduced motion and a snap under animator-scale 0
 * (`design/motion/motion-original.mjs:70-74` Focus ordinary,
 * `:57` reduced path).
 *
 * Alpha-only by design — reduced motion removes travel and scaling, and
 * the ordinary arrival must not add object motion of its own. Never
 * throws; a zero/negative duration simply lands on the end state.
 */
@Composable
fun AstraEnterTransition(
    motion: AstraMotionSpec.Resolved,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val entry = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        val durationMs = AstraMotionSpec.routineMs(motion)
        if (durationMs <= 0) {
            entry.snapTo(1f)
        } else {
            entry.animateTo(1f, astraTween(durationMs, AstraGentleEasing))
        }
    }
    Box(
        modifier.graphicsLayer {
            alpha = entry.value
        },
    ) {
        content()
    }
}
