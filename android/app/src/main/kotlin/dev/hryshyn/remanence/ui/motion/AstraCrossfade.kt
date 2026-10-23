package dev.hryshyn.remanence.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer

/**
 * Calm fade-through between two states of one surface.
 *
 * The outgoing composable is disposed immediately, as before: only the last
 * frame it drew survives, as a recorded display list that fades out quickly
 * on top; the live incoming content then rises from the background. Nothing
 * outgoing stays composed or interactive, and the incoming content takes
 * input from its first frame.
 *
 * The host must sit at a stable position above whatever [contentKey]
 * switches, otherwise it is recreated with the content and has nothing to
 * fade from. [animate] decides per change whether the old frame is kept
 * (sign-out passes false so private pixels never outlive the session).
 * The very first composition never fades.
 */
@Composable
fun AstraCrossfade(
    motion: AstraMotionSpec.Resolved,
    contentKey: Any?,
    modifier: Modifier = Modifier,
    animate: (from: Any?, to: Any?) -> Boolean = { _, _ -> true },
    content: @Composable () -> Unit,
) {
    val first = rememberGraphicsLayer()
    val second = rememberGraphicsLayer()
    val state = remember { CrossfadeHolder(contentKey) }
    val progress = remember { Animatable(1f) }
    val currentAnimate by rememberUpdatedState(animate)

    // Plain (non-snapshot) bookkeeping: the swap must take effect before the
    // next draw records, without invalidating the composition it runs in.
    if (contentKey != state.key) {
        val from = state.key
        state.key = contentKey
        state.fadePending = state.hasFrame && motion != AstraMotionSpec.Resolved.INSTANT && currentAnimate(from, contentKey)
        if (state.fadePending) {
            // The layer holding the last drawn frame stops recording and
            // becomes the outgoing snapshot; the next draw records into the
            // other one.
            state.recordingFirst = !state.recordingFirst
            state.frozenDirty = true
        }
    }

    LaunchedEffect(contentKey) {
        if (!state.fadePending) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        state.fadePending = false
        progress.snapTo(0f)
        progress.animateTo(1f, astraTween(AstraMotionSpec.crossfadeMs(motion), LinearEasing))
    }

    Box(modifier.drawWithContent {
        val recording = if (state.recordingFirst) first else second
        val frozen = if (state.recordingFirst) second else first
        // Read the animation unconditionally: a draw that skips the read is
        // never invalidated by it and would leave the new frame invisible.
        val animated = progress.value
        val p = if (state.fadePending) 0f else animated
        val fading = state.frozenDirty && p < 1f
        // Fade through: the old frame leaves first, then the new one rises
        // from the background. The two never share the screen at strength.
        recording.alpha = if (fading) FastOutSlowInEasing.transform(((p - OUT_SHARE) / (1f - OUT_SHARE)).coerceIn(0f, 1f)) else 1f
        recording.record { this@drawWithContent.drawContent() }
        drawLayer(recording)
        state.hasFrame = true
        if (fading) {
            val out = 1f - LinearOutSlowInEasing.transform((p / OUT_SHARE).coerceIn(0f, 1f))
            if (out > 0f) {
                frozen.alpha = out
                drawLayer(frozen)
            }
        } else if (state.frozenDirty) {
            // Transition finished (or was cancelled): drop the old frame's pixels.
            frozen.clear()
            state.frozenDirty = false
        }
    }) {
        content()
    }
}

/** Share of the transition spent fading the old frame out. */
private const val OUT_SHARE = 0.32f

private class CrossfadeHolder(var key: Any?) {
    var recordingFirst = true
    var hasFrame = false
    var frozenDirty = false
    var fadePending = false
}

private fun GraphicsLayer.clear() {
    record(
        density = androidx.compose.ui.unit.Density(1f),
        layoutDirection = androidx.compose.ui.unit.LayoutDirection.Ltr,
        size = androidx.compose.ui.unit.IntSize.Zero,
    ) {}
}
