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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.testTag
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

    /**
     * Capsule first visible: the raw clock instant where the *dissolve-eased*
     * progress reaches the 0.12 opacity keyframe (`≈114 ms` at 680).
     */
    fun capsuleFirstVisibleMs(d: Int): Int = (rawProgressAtEased(0.12f) * d).roundToInt()

    /**
     * Capsule fully present: the raw clock instant where the *dissolve-eased*
     * progress reaches the 0.72 opacity keyframe (`≈324 ms` at 680).
     */
    fun capsulePresentMs(d: Int): Int = (rawProgressAtEased(0.72f) * d).roundToInt()

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

    /**
     * Raw clock progress at which the dissolve easing reaches [eased]. The
     * frozen `element.animate` carries `easing: dissolve` on the *options*, so
     * the easing transforms the whole iteration before the authored
     * `0@0.00 / 0@0.12 / 1@0.72 / 1@1.00` opacity offsets are resolved; the
     * derived instants are therefore the inverse of the easing at 0.12/0.72.
     */
    private fun rawProgressAtEased(eased: Float): Float =
        cubicBezierInverse(AstraMotionSpec.EASING_DISSOLVE, eased)

    /**
     * Capsule opacity from the authored keyframes sampled on the
     * **dissolve-eased** progress (0 until eased 0.12, 1 from eased 0.72).
     */
    fun capsuleAlphaAt(progress: Float): Float {
        val eased = cubicBezierTransform(
            AstraMotionSpec.EASING_DISSOLVE,
            progress.coerceIn(0f, 1f),
        )
        return when {
            eased <= 0.12f -> 0f
            eased >= 0.72f -> 1f
            else -> (eased - 0.12f) / (0.72f - 0.12f)
        }
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
    // Capsule rect in the SAME root space as the published postcard rect
    // (`boundsInRoot()`), so one `a`/`b` pair drives both scale and
    // translation. `Modifier.offset` is parent-local, so the carry box's root
    // top-left is subtracted for the absolutely-placed ghost layers only.
    var capsuleRoot by remember { mutableStateOf<OpeningRect?>(null) }
    BoxWithConstraints(
        modifier.onGloballyPositioned { coordinates ->
            val measured = coordinates.boundsInRoot().toOpeningRect()
            if (capsuleRoot != measured) capsuleRoot = measured
        },
    ) {
        val capsule = capsuleRoot ?: OpeningRect(
            0f,
            0f,
            constraints.maxWidth.toFloat(),
            constraints.maxHeight.toFloat(),
        )
        val travel = cubicBezierTransform(AstraMotionSpec.EASING_TRAVEL, progress)
        // Backdrop: full-bleed, constant alpha, whole budget.
        Box(
            Modifier
                .fillMaxSize()
                .background(backdropColor),
        )
        if (!done) {
            // Neutral support bridge: root rect a → b, placed parent-local by
            // subtracting the carry box's root top-left. Elevation 14dp → 0dp.
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
                        IntOffset(
                            (bridge.left - capsule.left).roundToInt(),
                            (bridge.top - capsule.top).roundToInt(),
                        )
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
                    .clearAndSetSemantics {
                        invisibleToUser()
                        testTag = "astra_opening_bridge"
                    },
            )
            // Front (postcard) release: opacity 1 → 0 with dissolve easing,
            // drifting 25% toward the capsule center, over frontReleaseMs.
            // Root rect a, placed parent-local.
            val frontFraction = (progress * d / AstraOpeningSpec.frontReleaseMs(d)).coerceIn(0f, 1f)
            val frontEased = cubicBezierTransform(AstraMotionSpec.EASING_DISSOLVE, frontFraction)
            val driftX = (capsule.centerX - front.centerX) * 0.25f * frontEased
            val driftY = (capsule.centerY - front.centerY) * 0.25f * frontEased
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (front.left + driftX - capsule.left).roundToInt(),
                            (front.top + driftY - capsule.top).roundToInt(),
                        )
                    }
                    .size(
                        width = with(density) { front.width.toDp() },
                        height = with(density) { front.height.toDp() },
                    )
                    .graphicsLayer { alpha = 1f - frontEased }
                    .clearAndSetSemantics {
                        invisibleToUser()
                        testTag = "astra_opening_front"
                    },
            ) {
                frontContent()
            }
        }
        // Capsule transform: translate(a.center − b.center) scale(min) →
        // translate(0,0) scale(1), uniform, origin 50%/50% (graphicsLayer
        // default), on top of the ghosts. Translation is a delta, so the same
        // root-space `a`/`b` pair is correct without a local conversion.
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

/**
 * Inverse of [cubicBezierTransform]'s x → y curve: the raw x whose eased value
 * is [eased]. Bisection over the (monotonic) bezier parameter, then the x
 * polynomial — used to derive the clock instants of the eased opacity
 * keyframes. Pure Kotlin, no Compose types.
 */
internal fun cubicBezierInverse(control: FloatArray, eased: Float): Float {
    val target = eased.coerceIn(0f, 1f)
    val cy = 3f * control[1]
    val by = 3f * (control[3] - control[1]) - cy
    val ay = 1f - cy - by
    var lo = 0f
    var hi = 1f
    repeat(40) {
        val mid = (lo + hi) / 2f
        val y = ((ay * mid + by) * mid + cy) * mid
        if (y < target) lo = mid else hi = mid
    }
    val t = (lo + hi) / 2f
    val cx = 3f * control[0]
    val bx = 3f * (control[2] - control[0]) - cx
    val ax = 1f - cx - bx
    return ((ax * t + bx) * t + cx) * t
}
