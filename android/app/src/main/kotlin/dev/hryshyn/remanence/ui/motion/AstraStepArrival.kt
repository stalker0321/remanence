package dev.hryshyn.remanence.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Inner-step arrival for the Create flow (lookup → confirm → front →
 * content …). The root owns the single Home ↔ Create boundary arrival
 * ([AstraContextArrival]); this helper owns arrivals *between* steps.
 *
 * - First mount snaps to rest: it composes under the root boundary Carry,
 *   so replaying alpha/travel on top of it would double the fade
 *   (0.72 × 0.72) and stack travel. Later step changes animate.
 * - The outgoing step is removed synchronously: no exit fade, no retained
 *   private content, no navigation delay. The incoming step is interactive
 *   as soon as it is composed; the animation is purely graphical.
 * - Regimes reuse the [AstraMotionSpec] tokens via [rememberAstraMotion]:
 *   FULL is the 340 ms routine Carry (gentle dissolve + travel),
 *   REDUCED_SHORT is the 80 ms alpha-only crossfade (no travel),
 *   INSTANT snaps with zero duration.
 */
@Composable
fun AstraStepArrival(
    motion: AstraMotionSpec.Resolved,
    stepKey: Any?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // Latched on the first frame of this Create mount. Rotation does not
    // reset it (not keyed by configuration), so rotation never replays.
    var firstMount by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { firstMount = false }
    // A step change disposes the old content synchronously and starts the
    // incoming arrival from its initial values — nothing is retained.
    key(stepKey) {
        StepArrivalContent(
            motion = motion,
            snapFirst = firstMount,
            modifier = modifier,
            content = content,
        )
    }
}

@Composable
private fun StepArrivalContent(
    motion: AstraMotionSpec.Resolved,
    snapFirst: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val atRest = snapFirst || motion == AstraMotionSpec.Resolved.INSTANT
    val alpha = remember(motion) { Animatable(if (atRest) 1f else 0.72f) }
    val offset = remember(motion) {
        Animatable(if (!snapFirst && motion == AstraMotionSpec.Resolved.FULL) 8f else 0f)
    }
    val density = LocalDensity.current

    LaunchedEffect(motion) {
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
