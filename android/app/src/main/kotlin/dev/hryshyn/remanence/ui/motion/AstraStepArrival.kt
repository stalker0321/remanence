package dev.hryshyn.remanence.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Inner-step transition for the Create flow (lookup → confirm → front →
 * content …), also reused for auth substate swaps keyed the same way. The
 * root owns the route boundary; this helper owns the changes *between* steps.
 *
 * Steps cross-dissolve through [AstraCrossfade]: the outgoing step is still
 * removed synchronously, only its last frame melts away over the incoming
 * one. The first mount sits at rest under the root transition.
 */
@Composable
fun AstraStepArrival(
    motion: AstraMotionSpec.Resolved,
    stepKey: Any?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AstraCrossfade(motion = motion, contentKey = stepKey, modifier = modifier, content = content)
}
