package dev.hryshyn.remanence.ui.scan

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.hryshyn.remanence.capture.CapturedPostcard
import dev.hryshyn.remanence.ui.motion.AstraMotionSpec
import dev.hryshyn.remanence.ui.motion.LocalAstraCarryFront
import dev.hryshyn.remanence.ui.motion.OpeningRect
import dev.hryshyn.remanence.ui.motion.astraTween
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

/**
 * The recipient's own postcard while the capsule is looked up: the frozen
 * photo fades away as the card lifts off the table, straightens and settles
 * upright in the middle. Its settled bounds and face are published for the
 * capsule opening, which turns the same card over.
 */
@Composable
fun CapturedPostcardStage(
    postcard: CapturedPostcard,
    motion: AstraMotionSpec.Resolved,
    modifier: Modifier = Modifier,
    settled: Boolean = false,
) {
    val carryFront = LocalAstraCarryFront.current
    // A settled card (the drawn stand-in) has no photo to lift out of.
    val lift = remember(postcard) { Animatable(if (motion == AstraMotionSpec.Resolved.FULL && !settled) 0f else 1f) }
    val haptics = dev.hryshyn.remanence.ui.motion.rememberOpeningHaptics()
    LaunchedEffect(postcard, motion) {
        if (motion == AstraMotionSpec.Resolved.FULL && !settled) {
            delay(LIFT_HOLD_MS)
            haptics.lift()
            lift.animateTo(1f, astraTween(LIFT_MS, FastOutSlowInEasing))
        } else {
            lift.snapTo(1f)
        }
    }
    val painter = remember { PostcardPainter() }
    Box(
        modifier
            .fillMaxWidth()
            .height(STAGE_HEIGHT)
            .clipToBounds()
            .onGloballyPositioned { coordinates ->
                val origin = coordinates.positionInRoot()
                val target = settledCardRect(coordinates.size.toSize(), postcard)
                carryFront?.publish(
                    OpeningRect(
                        origin.x + target.left,
                        origin.y + target.top,
                        origin.x + target.right,
                        origin.y + target.bottom,
                    ),
                )
                carryFront?.publishFace(postcard)
            }
            .drawBehind {
                drawIntoCanvas { canvas ->
                    painter.draw(canvas.nativeCanvas, size, postcard, lift.value, density)
                }
            }
            .semantics {
                contentDescription = if (settled) "graphic postcard front" else "your postcard"
                role = Role.Image
            }
            .testTag("scan_captured_postcard"),
    )
}

/** Upright settled card: postcard proportions, centered, inside the stage. */
internal fun settledCardRect(stage: Size, postcard: CapturedPostcard): Rect {
    val ratio = if (postcard.landscape) postcard.aspect.toFloat() else 1f / postcard.aspect.toFloat()
    val maxWidth = stage.width * 0.86f
    val width = min(maxWidth, stage.height * ratio)
    val height = width / ratio
    val left = (stage.width - width) / 2f
    val top = (stage.height - height) / 2f
    return Rect(left, top, left + width, top + height)
}

/** Draws the card mapped from its photographed outline onto [dst] corners. */
internal class PostcardPainter {
    private val matrix = Matrix()
    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val cardPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF4F1EA.toInt() }
    private val path = Path()
    private val src = FloatArray(8)
    private val dst = FloatArray(8)
    private val photoRect = RectF()

    fun draw(canvas: android.graphics.Canvas, stage: Size, postcard: CapturedPostcard, lift: Float, density: Float) {
        val photo = postcard.photo
        if (photo.isRecycled) return
        // Frozen photo, covering the stage and centered on the card.
        val scale = max(stage.width / photo.width, stage.height / photo.height)
        val cardCenter = Offset(
            postcard.quad.map { it.x.toFloat() }.average().toFloat(),
            postcard.quad.map { it.y.toFloat() }.average().toFloat(),
        )
        // The covering edge can land a hair short of the stage in float math;
        // an inverted range would throw, so the overflow is never positive.
        val dx = (stage.width / 2f - cardCenter.x * scale).coerceIn(min(stage.width - photo.width * scale, 0f), 0f)
        val dy = (stage.height / 2f - cardCenter.y * scale).coerceIn(min(stage.height - photo.height * scale, 0f), 0f)
        val photoAlpha = 1f - lift
        if (photoAlpha > 0f) {
            photoRect.set(dx, dy, dx + photo.width * scale, dy + photo.height * scale)
            photoPaint.alpha = (255 * photoAlpha).toInt()
            canvas.drawBitmap(photo, null, photoRect, photoPaint)
        }
        val target = settledCardRect(stage, postcard)
        val corners = cornersFor(target, postcard)
        for (i in 0 until 4) {
            val p = postcard.quad[i]
            src[i * 2] = p.x.toFloat()
            src[i * 2 + 1] = p.y.toFloat()
            val fromX = dx + p.x.toFloat() * scale
            val fromY = dy + p.y.toFloat() * scale
            dst[i * 2] = fromX + (corners[i].x - fromX) * lift
            dst[i * 2 + 1] = fromY + (corners[i].y - fromY) * lift
        }
        path.reset()
        path.moveTo(dst[0], dst[1])
        for (i in 1 until 4) path.lineTo(dst[i * 2], dst[i * 2 + 1])
        path.close()
        if (lift > 0f) {
            shadowPaint.setShadowLayer(22f * density * lift, 0f, 10f * density * lift, 0x55000000)
            canvas.drawPath(path, shadowPaint)
        }
        matrix.setPolyToPoly(src, 0, dst, 0, 4)
        canvas.save()
        canvas.clipPath(path)
        canvas.drawBitmap(photo, matrix, cardPaint)
        canvas.restore()
    }

    /** Target corners in the photographed corner order (TL, TR, BR, BL). */
    private fun cornersFor(target: Rect, postcard: CapturedPostcard): List<Offset> =
        listOf(target.topLeft, target.topRight, target.bottomRight, target.bottomLeft)
}

/** Draws the settled, straightened card into [size] (the opening's face). */
fun drawPostcardFace(canvas: android.graphics.Canvas, size: Size, postcard: CapturedPostcard, painter: Matrix, paint: Paint) {
    val photo = postcard.photo
    if (photo.isRecycled) return
    val src = FloatArray(8)
    postcard.quad.forEachIndexed { i, p ->
        src[i * 2] = p.x.toFloat()
        src[i * 2 + 1] = p.y.toFloat()
    }
    val dst = floatArrayOf(0f, 0f, size.width, 0f, size.width, size.height, 0f, size.height)
    painter.setPolyToPoly(src, 0, dst, 0, 4)
    canvas.save()
    canvas.clipRect(0f, 0f, size.width, size.height)
    canvas.drawBitmap(photo, painter, paint)
    canvas.restore()
}

private val STAGE_HEIGHT = 300.dp
private const val LIFT_HOLD_MS = 160L
private const val LIFT_MS = 900
