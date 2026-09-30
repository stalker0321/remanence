package dev.hryshyn.remanence.ui.motion

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Opening Carry: the scan-accepted → capsule handoff (freeze `motion.mjs:78–108`,
 * `guide.mjs` motion row "Verified front → whole capsule").
 *
 * Pure geometry ([OpeningRect]) and derived timing ([AstraOpeningSpec]) stay out
 * of composition so plain JVM tests pin them; [AstraOpeningCarry] plays the
 * beats. Ghost layers (bridge + front) sit *below* the capsule content, are
 * inert and hidden from accessibility, and are removed synchronously at `t = d`
 * (or on disposal). The front slot re-renders the decorative postcard only —
 * never capsule or private content.
 */
data class OpeningRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val center: Offset get() = Offset(centerX, centerY)
}

fun Rect.toOpeningRect(): OpeningRect = OpeningRect(left, top, right, bottom)

/** Derived opening geometry/timing. Pure Kotlin: no Compose import in any member. */
object AstraOpeningSpec {
    /** Front release duration: 60% of the opening budget (408 ms at 680). */
    fun frontReleaseMs(d: Int): Int = (d * 0.6f).toInt()

    /** Capsule fully present: the 0.72 keyframe (490 ms at 680). */
    fun capsulePresentMs(d: Int): Int = Math.round(d * 0.72f)

    /** Capsule first visible: the 0.12 keyframe (81 ms at 680). */
    fun capsuleFirstVisibleMs(d: Int): Int = (d * 0.12f).toInt()

    /** Geometry channels use the pinned travel easing; identical array reference. */
    val GEOMETRY_EASING: FloatArray = AstraMotionSpec.EASING_TRAVEL

    /** Opacity channels use the pinned dissolve easing; identical array reference. */
    val OPACITY_EASING: FloatArray = AstraMotionSpec.EASING_DISSOLVE

    /** Uniform inscribed scale `min(a.w/b.w, a.h/b.h)`; 1 when the rects match. */
    fun scaleOf(a: OpeningRect, b: OpeningRect): Float =
        min(a.width / b.width, a.height / b.height)

    /** Capsule travel offset at [fraction]: `a.center − b.center` → `(0,0)`. */
    fun translateAt(a: OpeningRect, b: OpeningRect, fraction: Float): Offset {
        val k = 1f - fraction
        return Offset((a.centerX - b.centerX) * k, (a.centerY - b.centerY) * k)
    }

    /** Ghost layers exist only under full motion; reduced/instant render none. */
    fun hasGhosts(motion: AstraMotionSpec.Resolved): Boolean =
        motion == AstraMotionSpec.Resolved.FULL

    /** Neutral support bridge fill (`motion.mjs:98–101` `#e4e2ed`). */
    const val BRIDGE_COLOR: Int = 0xFFE4E2ED.toInt()

    /** Capsule opacity from the authored keyframes (0@0.00, 0@0.12, 1@0.72, 1@1.00). */
    fun capsuleAlphaAt(fraction: Float): Float = when {
        fraction <= 0.12f -> 0f
        fraction >= 0.72f -> 1f
        else -> (fraction - 0.12f) / (0.72f - 0.12f)
    }
}

/**
 * One-shot holder for the Scan postcard bounds. Published from layout,
 * consumed once by the Capsule entry; clearing on read bounds the write to a
 * single snapshot update so no recomposition loop can form.
 */
class AstraCarryFront {
    var rect: OpeningRect? by mutableStateOf(null)
        private set

    fun publish(value: OpeningRect) {
        rect = value
    }

    fun clear() {
        rect = null
    }

    /**
     * Returns the published rect when [active], clearing it so the opening can
     * run at most once per handoff. Writes only when a value existed.
     */
    fun consumeIf(active: Boolean): OpeningRect? {
        val current = rect
        if (active && current != null) {
            rect = null
        }
        return if (active) current else null
    }
}

val LocalAstraCarryFront: ProvidableCompositionLocal<AstraCarryFront?> =
    compositionLocalOf { null }

/**
 * Plays the opening over [content] (the capsule, always on top, composed and
 * hit-testable at frame 0). `front == null` behaves exactly as
 * [AstraContextArrival] (freeze fallback `motion.mjs:80`).
 *
 * @param front postcard rect in the same root pixels as this layout.
 * @param backdropColor full-bleed field substituting the dropped outgoing pane.
 */
@Composable
fun AstraOpeningCarry(
    motion: AstraMotionSpec.Resolved,
    modifier: Modifier = Modifier,
    front: OpeningRect?,
    backdropColor: Color,
    frontContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    if (front == null) {
        AstraContextArrival(motion = motion, modifier = modifier) {
            content()
        }
        return
    }
    val d = AstraMotionSpec.openingMs(motion)
    if (d <= 0 || !AstraOpeningSpec.hasGhosts(motion)) {
        // Reduced/instant regimes: fully verified capsule, no travel, no ghosts.
        Box(modifier) {
            content()
        }
        return
    }
    // Linear 0→1 clock; easings apply per channel below so the authored
    // keyframes stay in absolute timeline fractions.
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(d) {
        animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = tween(durationMillis = d, easing = LinearEasing),
        ) { value, _ ->
            progress = value
        }
    }
    val done = progress >= 1f
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val capsule = OpeningRect(0f, 0f, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val travel = cubicBezierTransform(AstraMotionSpec.EASING_TRAVEL, progress)
        // Backdrop: full-bleed, constant alpha, whole budget.
        Box(
            Modifier
                .fillMaxSize()
                .background(backdropColor),
        )
        if (!done) {
            // Neutral support bridge: rect a → b, elevation 14dp → 0dp.
            // Shadow tint #24273d20 has no Compose equivalent (known
            // approximation, device-check only).
            val bridge = OpeningRect(
                front.left + (capsule.left - front.left) * travel,
                front.top + (capsule.top - front.top) * travel,
                front.right + (capsule.right - front.right) * travel,
                front.bottom + (capsule.bottom - front.bottom) * travel,
            )
            Box(
                Modifier
                    .offset {
                        IntOffset(bridge.left.roundToInt(), bridge.top.roundToInt())
                    }
                    .size(
                        width = with(density) { bridge.width.toDp() },
                        height = with(density) { bridge.height.toDp() },
                    )
                    .shadow(
                        elevation = 14.dp * (1f - travel),
                        shape = RectangleShape,
                        clip = false,
                    )
                    .background(Color(AstraOpeningSpec.BRIDGE_COLOR))
                    .semantics { invisibleToUser() }
                    .testTag("astra_opening_bridge"),
            )
            // Front (postcard) release: opacity 1 → 0 with dissolve easing,
            // drifting 25% toward the capsule center, over frontReleaseMs.
            val frontFraction = (progress * d / AstraOpeningSpec.frontReleaseMs(d)).coerceIn(0f, 1f)
            val frontEased = cubicBezierTransform(AstraMotionSpec.EASING_DISSOLVE, frontFraction)
            val driftX = (capsule.centerX - front.centerX) * 0.25f * frontEased
            val driftY = (capsule.centerY - front.centerY) * 0.25f * frontEased
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (front.left + driftX).roundToInt(),
                            (front.top + driftY).roundToInt(),
                        )
                    }
                    .size(
                        width = with(density) { front.width.toDp() },
                        height = with(density) { front.height.toDp() },
                    )
                    .graphicsLayer { alpha = 1f - frontEased }
                    .semantics { invisibleToUser() }
                    .testTag("astra_opening_front"),
            ) {
                frontContent()
            }
        }
        // Capsule transform: translate(a.center − b.center) scale(min) →
        // translate(0,0) scale(1), uniform, origin 50%/50% (graphicsLayer
        // default), on top of the ghosts.
        val offset = AstraOpeningSpec.translateAt(front, capsule, travel)
        val minScale = AstraOpeningSpec.scaleOf(front, capsule)
        val scale = minScale + (1f - minScale) * travel
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = offset.x
                    translationY = offset.y
                    scaleX = scale
                    scaleY = scale
                    alpha = AstraOpeningSpec.capsuleAlphaAt(progress)
                },
        ) {
            content()
        }
    }
}

/** Cubic-bezier evaluation for a pinned [c1x,c1y,c2x,c2y] control array. */
internal fun cubicBezierTransform(control: FloatArray, x: Float): Float {
    // Newton-Raphson solve for t given x on bezier(0,c1x,c2x,1), then sample y.
    val cx = 3f * control[0]
    val bx = 3f * (control[2] - control[0]) - cx
    val ax = 1f - cx - bx
    val cy = 3f * control[1]
    val by = 3f * (control[3] - control[1]) - cy
    val ay = 1f - cy - by
    var t = x.coerceIn(0f, 1f)
    repeat(8) {
        val current = ((ax * t + bx) * t + cx) * t - x
        val slope = (3f * ax * t + 2f * bx) * t + cx
        if (slope == 0f) return@repeat
        t -= current / slope
    }
    t = t.coerceIn(0f, 1f)
    return ((ay * t + by) * t + cy) * t
}
