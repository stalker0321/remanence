package dev.hryshyn.remanence.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Calm arrival between two states of one surface.
 *
 * The outgoing composable is disposed immediately and leaves with it; the
 * live incoming content rises from the background. Nothing outgoing stays
 * composed or interactive, and the incoming content takes input from its
 * first frame.
 *
 * The outgoing frame is deliberately not kept: a recorded layer draws the
 * children's live render nodes, so a "frozen" copy showed the incoming
 * content, faded it out and brought it back - a visible blink.
 *
 * The host must sit at a stable position above whatever [contentKey]
 * switches, otherwise it is recreated with the content and has nothing to
 * animate. [animate] decides per change whether the arrival plays (sign-out
 * passes false). The very first composition never fades.
 */
@Composable
fun AstraCrossfade(
    motion: AstraMotionSpec.Resolved,
    contentKey: Any?,
    modifier: Modifier = Modifier,
    animate: (from: Any?, to: Any?) -> Boolean = { _, _ -> true },
    content: @Composable () -> Unit,
) {
    val state = remember { CrossfadeHolder(contentKey) }
    val progress = remember { Animatable(1f) }
    val currentAnimate by rememberUpdatedState(animate)

    // Plain (non-snapshot) bookkeeping: the first frame of the new content
    // must already draw transparent, before the effect below starts.
    if (contentKey != state.key) {
        val from = state.key
        state.key = contentKey
        state.fadePending = motion != AstraMotionSpec.Resolved.INSTANT && currentAnimate(from, contentKey)
    }

    LaunchedEffect(contentKey) {
        if (!state.fadePending) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        state.fadePending = false
        progress.animateTo(1f, astraTween(AstraMotionSpec.crossfadeMs(motion), LinearOutSlowInEasing))
    }

    Box(modifier.graphicsLayer {
        // Read the animation unconditionally so every frame tracks it.
        val animated = progress.value
        alpha = if (state.fadePending) 0f else animated
    }) {
        content()
    }
}

private class CrossfadeHolder(var key: Any?) {
    var fadePending = false
}
