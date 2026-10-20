package dev.hryshyn.remanence.ui.create

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.CropTransform
import dev.hryshyn.remanence.ui.collage.FixedFrameCropCorrection
import dev.hryshyn.remanence.ui.collage.NormalizedCrop
import dev.hryshyn.remanence.ui.hold.HoldButton
import dev.hryshyn.remanence.ui.hold.HoldColors
import dev.hryshyn.remanence.ui.hold.HoldTextButton
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun CropSlotPicker(
    sources: List<GeneratorPreviewSource>,
    corrections: Map<String, CreateViewModel.CropCorrection>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("crop_slots"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sources.forEachIndexed { index, source ->
            HoldTextButton(onClick = { onOpen(source.contentId) }, modifier = Modifier.testTag("crop_slot_$index")) {
                Text(stringResource(R.string.crop_photo_number, index + 1) +
                    if (corrections.containsKey(source.contentId)) " ✓" else "")
            }
        }
    }
}

/** Full upright source with a movable, slot-aspect crop frame. No source pixels are hidden. */
@Composable
fun CropStageContent(
    source: GeneratorPreviewSource,
    slotAspect: Float,
    baseCrop: GeneratorExpression.CropWindow?,
    correction: CreateViewModel.CropCorrection?,
    onUpdate: (zoom: Float, panX: Float, panY: Float) -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(source) {
        BitmapFactory.decodeByteArray(source.jpegBytes, 0, source.jpegBytes.size)?.asImageBitmap()
    }
    val sourceAspect = source.widthPx.coerceAtLeast(1).toDouble() / source.heightPx.coerceAtLeast(1)
    val base = remember(baseCrop, slotAspect, sourceAspect) {
        baseCrop?.let { FixedFrameCropCorrection.normalizedCrop(it) } ?: run {
            val ratio = slotAspect.takeIf { it.isFinite() && it > 0 }?.toDouble() ?: sourceAspect
            val width = min(1.0, ratio / sourceAspect)
            val height = min(1.0, sourceAspect / ratio)
            NormalizedCrop((1 - width) / 2, (1 - height) / 2, width, height)
        }
    }
    val transform = CropTransform(
        correction?.zoom?.toDouble() ?: 1.0,
        correction?.panX?.toDouble() ?: 0.0,
        correction?.panY?.toDouble() ?: 0.0,
    )
    val retained = CropEditorGeometry.window(base, transform) ?: base
    val currentTransform by rememberUpdatedState(transform)
    val currentUpdate by rememberUpdatedState(onUpdate)
    val density = LocalDensity.current
    val cropGridDescription = stringResource(R.string.create_audit_crop_grid)
    val cropPosition = stringResource(R.string.media_crop_position,
        (retained.x * 100).roundToInt(), (retained.y * 100).roundToInt(),
        (retained.width * 100).roundToInt(), (retained.height * 100).roundToInt())
    // Alternative inputs feed exactly the touch gesture geometry and clamp.
    fun adjust(dx: Double = 0.0, dy: Double = 0.0, zoom: Double = 1.0): Boolean {
        val window = CropEditorGeometry.window(base, currentTransform) ?: return false
        val next = CropEditorGeometry.gesture(base, currentTransform, dx, dy, zoom,
            window.x + window.width / 2, window.y + window.height / 2) ?: return false
        currentUpdate(next.z.toFloat(), next.xPercent.toFloat(), next.yPercent.toFloat())
        return true
    }
    val actions = listOf(
        CustomAccessibilityAction(stringResource(R.string.media_crop_left)) { adjust(dx = -.05) },
        CustomAccessibilityAction(stringResource(R.string.media_crop_right)) { adjust(dx = .05) },
        CustomAccessibilityAction(stringResource(R.string.media_crop_up)) { adjust(dy = -.05) },
        CustomAccessibilityAction(stringResource(R.string.media_crop_down)) { adjust(dy = .05) },
        CustomAccessibilityAction(stringResource(R.string.media_crop_zoom_in)) { adjust(zoom = 1.1) },
        CustomAccessibilityAction(stringResource(R.string.media_crop_zoom_out)) { adjust(zoom = 1 / 1.1) },
    )
    BoxWithConstraints(modifier.fillMaxWidth().testTag("crop_stage")) {
        // Production provides bounded height; the fallback supports embedded previews.
        val editorHeight = if (constraints.hasBoundedHeight) maxHeight else 540.dp
        Column(Modifier.fillMaxWidth().height(editorHeight), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.crop_one_photograph),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = HoldColors.Muted,
                )
                HoldTextButton(onClick = onBack, modifier = Modifier.testTag("crop_back")) {
                    Text(stringResource(R.string.crop_cancel))
                }
            }
            Text(stringResource(R.string.hold_crop_title),
                style = MaterialTheme.typography.headlineLarge, modifier = Modifier.testTag("crop_stage_label"))
            Text(
                stringResource(R.string.media_crop_hint), style = MaterialTheme.typography.bodyMedium,
                color = HoldColors.Muted, modifier = Modifier.testTag("crop_stage_hint"),
            )
            BoxWithConstraints(
                Modifier.fillMaxWidth().weight(1f).background(HoldColors.Field).testTag("crop_workspace"),
                contentAlignment = Alignment.Center,
            ) {
                val imageWidthPx = min(constraints.maxWidth.toDouble(), constraints.maxHeight * sourceAspect).toFloat()
                val imageHeightPx = (imageWidthPx / sourceAspect).toFloat()
                val fittedWidth = with(density) { imageWidthPx.toDp() }
                val fittedHeight = with(density) { imageHeightPx.toDp() }
                Box(
                    Modifier.size(fittedWidth, fittedHeight)
                        .pointerInput(source.contentId, base, imageWidthPx, imageHeightPx) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var gestureTransform = currentTransform
                                val initial = CropEditorGeometry.window(base, gestureTransform) ?: base
                                val x = down.position.x / imageWidthPx
                                val y = down.position.y / imageHeightPx
                                val startedOnFrame = x >= initial.x && x <= initial.x + initial.width &&
                                    y >= initial.y && y <= initial.y + initial.height
                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.any { it.isConsumed }) break
                                    val pinch = event.changes.count { it.pressed } > 1
                                    if (startedOnFrame || pinch) {
                                        val pan = event.calculatePan()
                                        val zoom = event.calculateZoom()
                                        val centroid = event.calculateCentroid(useCurrent = false)
                                        if (pan != Offset.Zero || zoom != 1f) {
                                            CropEditorGeometry.gesture(
                                                base, gestureTransform,
                                                pan.x.toDouble() / imageWidthPx,
                                                pan.y.toDouble() / imageHeightPx,
                                                zoom.toDouble(),
                                                centroid.x.toDouble() / imageWidthPx,
                                                centroid.y.toDouble() / imageHeightPx,
                                            )?.let { next ->
                                                gestureTransform = next
                                                currentUpdate(next.z.toFloat(), next.xPercent.toFloat(), next.yPercent.toFloat())
                                            }
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }.testTag("crop_source_surface"),
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = stringResource(R.string.crop_original_photo),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.matchParentSize().testTag("crop_image"),
                        )
                    }
                    Canvas(Modifier.matchParentSize()) {
                        val left = (retained.x * size.width).toFloat()
                        val top = (retained.y * size.height).toFloat()
                        val right = ((retained.x + retained.width) * size.width).toFloat()
                        val bottom = ((retained.y + retained.height) * size.height).toFloat()
                        val shade = Color.Black.copy(alpha = .42f)
                        drawRect(shade, Offset.Zero, Size(size.width, top))
                        drawRect(shade, Offset(0f, bottom), Size(size.width, (size.height - bottom).coerceAtLeast(0f)))
                        drawRect(shade, Offset(0f, top), Size(left, bottom - top))
                        drawRect(shade, Offset(right, top), Size((size.width - right).coerceAtLeast(0f), bottom - top))
                    }
                    Canvas(
                        Modifier.offset {
                            IntOffset((retained.x * imageWidthPx).roundToInt(), (retained.y * imageHeightPx).roundToInt())
                        }.size(
                            with(density) { (retained.width * imageWidthPx).toFloat().toDp() },
                            with(density) { (retained.height * imageHeightPx).toFloat().toDp() },
                        ).semantics {
                            contentDescription = cropGridDescription
                            stateDescription = cropPosition
                            customActions = actions
                        }.onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                Key.DirectionLeft -> adjust(dx = -.05)
                                Key.DirectionRight -> adjust(dx = .05)
                                Key.DirectionUp -> adjust(dy = -.05)
                                Key.DirectionDown -> adjust(dy = .05)
                                Key.Equals, Key.Plus -> adjust(zoom = 1.1)
                                Key.Minus -> adjust(zoom = 1 / 1.1)
                                else -> false
                            }
                        }.focusable().testTag("crop_frame"),
                    ) {
                        val stroke = 2.dp.toPx()
                        for (third in 1..2) {
                            drawLine(Color.White.copy(alpha = .4f), Offset(size.width * third / 3, 0f),
                                Offset(size.width * third / 3, size.height), 1.dp.toPx())
                            drawLine(Color.White.copy(alpha = .4f), Offset(0f, size.height * third / 3),
                                Offset(size.width, size.height * third / 3), 1.dp.toPx())
                        }
                        drawRect(Color.White, Offset(stroke / 2, stroke / 2),
                            Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f)), style = Stroke(stroke))
                    }
                }
            }
            HoldButton(onClick = onDone, modifier = Modifier.fillMaxWidth().testTag("crop_done")) {
                Text(stringResource(R.string.crop_done), Modifier.weight(1f))
                Text("→", style = MaterialTheme.typography.titleLarge)
            }
            HoldTextButton(onClick = onReset, modifier = Modifier.fillMaxWidth().testTag("crop_reset")) {
                Text(stringResource(R.string.crop_reset))
            }
        }
    }
}
