package dev.hryshyn.remanence.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The arriving half of Astra's routine Carry. The previous route is removed
 * synchronously: retaining it for an exit crossfade could keep a captured
 * front, draft, or decrypted capsule visible after its grant/session ends.
 *
 * Ownership: exactly one arrival per route boundary, owned by the root.
 * Inner flow steps must not wrap themselves: nesting multiplies the alpha
 * (0.72 * 0.72) and stacks travel on top of the boundary Carry.
 *
 * This has no navigation delay. A quick destination is interactive as soon
 * as it is composed, and reduced motion removes the spatial travel entirely.
 */
@Composable
fun AstraContextArrival(
    motion: AstraMotionSpec.Resolved,
    modifier: Modifier = Modifier,
    contentKey: Any? = null,
    content: @Composable () -> Unit,
) {
    val alpha = remember(motion, contentKey) { Animatable(if (motion == AstraMotionSpec.Resolved.INSTANT) 1f else 0.72f) }
    val offset = remember(motion, contentKey) { Animatable(if (motion == AstraMotionSpec.Resolved.FULL) 8f else 0f) }
    val density = LocalDensity.current

    LaunchedEffect(motion, contentKey) {
        val duration = AstraMotionSpec.routineMs(motion)
        if (duration <= 0) {
            alpha.snapTo(1f)
            offset.snapTo(0f)
        } else {
            // Both start in the same frame; no blank interstitial or second beat.
            launch { alpha.animateTo(1f, astraTween(duration, AstraDissolveEasing)) }
            launch { offset.animateTo(0f, astraTween(duration, AstraTravelEasing)) }
        }
    }

    Box(modifier.graphicsLayer {
        this.alpha = alpha.value
        translationY = with(density) { offset.value.dp.toPx() }
    }) {
        content()
    }
}
