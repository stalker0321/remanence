package dev.hryshyn.remanence.ui.motion

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.hryshyn.remanence.capture.CapturedPostcard
import dev.hryshyn.remanence.ui.scan.drawPostcardFace
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * The capsule opening: the recipient's own card lifts, turns over in the
 * middle of the screen, and its back is the capsule, which opens out to the
 * full screen and develops from a soft, faded print into full colour.
 *
 * The capsule content is composed once, from the first frame, at one stable
 * position; only its layer transform changes, so nothing inside it restarts.
 * The face is the recipient's photo of the card, never capsule content.
 * A tap skips to the end.
 */
@Composable
fun AstraPostcardFlip(
    front: OpeningRect,
    face: CapturedPostcard,
    backdropColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val clock = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) {
        clock.animateTo(1f, tween(FLIP_TOTAL_MS, easing = LinearEasing))
    }
    LaunchedEffect(Unit) {
        snapshotFlow { clock.value }
            .map { t -> (if (t >= FLIP_MID) 1 else 0) + (if (t >= EXPAND_END) 1 else 0) }
            .distinctUntilChanged()
            .collect { stage ->
                when (stage) {
                    1 -> haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    2 -> haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
    }
    val running by remember { androidx.compose.runtime.derivedStateOf { clock.value < 1f } }
    var origin by remember { mutableStateOf<Rect?>(null) }
    val facePaint = remember { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }
    val faceMatrix = remember { Matrix() }
    val shadowPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF4F1EA.toInt() } }

    Box(
        modifier
            .fillMaxSize()
            .background(backdropColor)
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInRoot()
                if (origin != bounds) origin = bounds
            },
    ) {
        // Back: the capsule itself, windowed to the card while it turns.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val frame = flipFrame(clock.value, front, origin, size)
                    val scale = frame.coverScale(size)
                    val showingBack = frame.angle >= 90f
                    alpha = if (showingBack) 1f else 0f
                    scaleX = scale
                    scaleY = scale
                    translationX = frame.rect.center.x - size.width / 2f
                    translationY = frame.rect.center.y - size.height / 2f
                    rotationY = if (showingBack) frame.angle - 180f else 0f
                    cameraDistance = CAMERA_DISTANCE * density
                    transformOrigin = TransformOrigin.Center
                    shape = WindowShape(frame.rect.width / scale, frame.rect.height / scale, frame.cornerPx / scale)
                    clip = frame.expand < 1f
                    renderEffect = developEffect(frame.develop)
                },
        ) {
            content()
        }
        // Face: the recipient's card, until it has turned edge-on.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val frame = flipFrame(clock.value, front, origin, size)
                    alpha = if (frame.angle < 90f) 1f else 0f
                    translationX = frame.rect.center.x - size.width / 2f
                    translationY = frame.rect.center.y - size.height / 2f
                    rotationY = frame.angle
                    cameraDistance = CAMERA_DISTANCE * density
                }
                .drawBehind {
                    val frame = flipFrame(clock.value, front, origin, size)
                    if (frame.angle >= 90f) return@drawBehind
                    val w = frame.rect.width
                    val h = frame.rect.height
                    val left = (size.width - w) / 2f
                    val top = (size.height - h) / 2f
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        val lift = frame.lift
                        shadowPaint.setShadowLayer((10f + 18f * lift) * density, 0f, (4f + 10f * lift) * density, 0x55000000)
                        native.drawRect(left, top, left + w, top + h, shadowPaint)
                        native.save()
                        native.translate(left, top)
                        drawPostcardFace(native, Size(w, h), face, faceMatrix, facePaint)
                        native.restore()
                    }
                }
                .clearAndSetSemantics {
                    invisibleToUser()
                    testTag = "astra_postcard_flip_face"
                },
        )
        // Skip: any tap lands the capsule at once.
        if (running) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { scope.launch { clock.snapTo(1f) } }
                    }
                    .testTag("astra_postcard_flip_skip"),
            )
        }
    }
}

/** One sampled instant of the opening, in the container's own coordinates. */
internal data class FlipFrame(
    val rect: Rect,
    val angle: Float,
    val lift: Float,
    val expand: Float,
    val develop: Float,
    val cornerPx: Float,
) {
    /** Uniform scale that makes the full-size capsule cover [rect]. */
    fun coverScale(full: Size): Float =
        maxOf(rect.width / full.width, rect.height / full.height).coerceIn(0.01f, 1f)
}

internal fun flipFrame(t: Float, front: OpeningRect, origin: Rect?, size: Size): FlipFrame {
    val ox = origin?.left ?: 0f
    val oy = origin?.top ?: 0f
    val start = Rect(front.left - ox, front.top - oy, front.right - ox, front.bottom - oy)
    val ratio = start.width / start.height
    val centeredWidth = min(size.width * 0.80f, size.height * 0.62f * ratio)
    val centeredHeight = centeredWidth / ratio
    val centered = Rect(
        (size.width - centeredWidth) / 2f,
        (size.height - centeredHeight) / 2f,
        (size.width + centeredWidth) / 2f,
        (size.height + centeredHeight) / 2f,
    )
    val full = Rect(0f, 0f, size.width, size.height)
    val lift = phase(t, 0f, LIFT_END)
    val flip = FastOutSlowInEasing.transform(phase(t, FLIP_START, FLIP_END))
    val expand = FastOutSlowInEasing.transform(phase(t, FLIP_END, EXPAND_END))
    val develop = phase(t, DEVELOP_START, 1f)
    val liftScale = 1f + 0.05f * FastOutSlowInEasing.transform(lift) * (1f - flip)
    val travelled = lerp(start, centered, flip)
    val lifted = Rect(
        travelled.center.x - travelled.width * liftScale / 2f,
        travelled.center.y - travelled.height * liftScale / 2f,
        travelled.center.x + travelled.width * liftScale / 2f,
        travelled.center.y + travelled.height * liftScale / 2f,
    )
    val rect = if (expand > 0f) lerp(centered, full, expand) else lifted
    return FlipFrame(
        rect = rect,
        angle = 180f * flip,
        lift = lift,
        expand = expand,
        develop = develop,
        cornerPx = CARD_CORNER_PX * (1f - expand),
    )
}

private fun phase(t: Float, from: Float, to: Float): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)

private fun lerp(a: Rect, b: Rect, f: Float) = Rect(
    a.left + (b.left - a.left) * f,
    a.top + (b.top - a.top) * f,
    a.right + (b.right - a.right) * f,
    a.bottom + (b.bottom - a.bottom) * f,
)

/** Soft, faded print sharpening into full colour (API 31+; plain otherwise). */
private fun developEffect(progress: Float): androidx.compose.ui.graphics.RenderEffect? {
    if (progress >= 1f || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val eased = FastOutSlowInEasing.transform(progress)
    val saturation = ColorMatrix().apply { setSaturation(0.15f + 0.85f * eased) }
    val warm = ColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, 18f * (1f - eased),
            0f, 1f, 0f, 0f, 8f * (1f - eased),
            0f, 0f, 1f, 0f, -10f * (1f - eased),
            0f, 0f, 0f, 1f, 0f,
        ),
    ).apply { postConcat(saturation) }
    val color = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(warm))
    val blurRadius = 22f * (1f - eased)
    val effect = if (blurRadius >= 0.5f) {
        RenderEffect.createChainEffect(color, RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP))
    } else {
        color
    }
    return effect.asComposeRenderEffect()
}

/** Centered rounded window of [width] x [height] inside the layer. */
private class WindowShape(val width: Float, val height: Float, val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val left = (size.width - width) / 2f
        val top = (size.height - height) / 2f
        return Outline.Rounded(
            RoundRect(left, top, left + width, top + height, androidx.compose.ui.geometry.CornerRadius(radius)),
        )
    }
}

internal const val FLIP_TOTAL_MS = 2000
private const val LIFT_END = 0.10f
private const val FLIP_START = 0.06f
private const val FLIP_END = 0.46f
internal const val FLIP_MID = (FLIP_START + FLIP_END) / 2f
internal const val EXPAND_END = 0.70f
private const val DEVELOP_START = 0.48f
private const val CAMERA_DISTANCE = 14f
private const val CARD_CORNER_PX = 18f
