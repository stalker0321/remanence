package dev.hryshyn.remanence.ui.create

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.FixedFrameCropCorrection
import dev.hryshyn.remanence.ui.collage.NormalizedCrop
import dev.hryshyn.remanence.ui.collage.PanDirection
import dev.hryshyn.remanence.R
import java.util.Locale

/**
 * T150 crop seam surfaces (Astra `guide.mjs:29,:41`, `render.mjs:58`):
 * per-photo fixed-frame correction entry plus the crop stage.
 *
 * The stage is the frozen `.crop-stage`/`.crop-window` contract, not a
 * source-aspect preview:
 * - the frame is the **fixed window at the slot's aspect**
 *   (`--crop-ratio: slot.w/slot.h`), clipped (`overflow:hidden`) so the frame
 *   edge IS the retained window;
 * - the source excerpt (the approved `baseCrop`) fills the frame and the
 *   fragment carries the frozen `translate(x%, y%) scale(z)` transform about
 *   the centre, percent of the frame box (`.fragment`);
 * - the `.crop-grid` overlay (2 px white border + thirds) sits above the
 *   fragment and is never transformed.
 *
 * Values are display state only until [CreateViewModel.applyCropAndClose]
 * runs the same transform through the accepted T157 `correct()` seam into a
 * new sealed window and frozen hash. Absent entry == identity.
 */
@Composable
fun CropSlotPicker(
    sources: List<GeneratorPreviewSource>,
    corrections: Map<String, CreateViewModel.CropCorrection>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().testTag("crop_slots")) {
        Text(
            androidx.compose.ui.res.stringResource(R.string.hold_crop_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("crop_slots_label"),
        )
        Spacer(Modifier.height(4.dp))
        // Four/five slot buttons must stay reachable on narrow screens: the
        // row scrolls horizontally instead of clipping the trailing slots.
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            sources.forEachIndexed { index, source ->
                val corrected = corrections.containsKey(source.contentId)
                HoldTextButtonCompat(
                    text = "Photo ${index + 1}" + if (corrected) " ✓" else "",
                    tag = "crop_slot_$index",
                    onClick = { onOpen(source.contentId) },
                )
            }
        }
    }
}

@Composable
private fun HoldTextButtonCompat(text: String, tag: String, onClick: () -> Unit) {
    dev.hryshyn.remanence.ui.hold.HoldTextButton(
        onClick = onClick,
        modifier = Modifier.testTag(tag),
    ) { Text(text) }
}

/**
 * Fixed-frame crop stage.
 *
 * @param slotAspect width/height of the destination slot; the frame keeps it
 *   exactly (Astra `render.mjs:58` `--crop-ratio:${slot[2]/slot[3]}`).
 * @param baseCrop the approved sealed window for this slot (`0..10000` units);
 *   the source excerpt drawn under the frame. `null` falls back to the whole
 *   source so the stage still renders fail-safe.
 */
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
    val zoom = correction?.zoom ?: 1f
    val panX = correction?.panX ?: 0f
    val panY = correction?.panY ?: 0f
    val base = remember(baseCrop) {
        baseCrop?.let { FixedFrameCropCorrection.normalizedCrop(it) }
            ?: NormalizedCrop(0.0, 0.0, 1.0, 1.0)
    }
    // The retained window is derived with the SAME accepted math the
    // ViewModel uses to seal the correction, so the display can never show
    // a window different from the one that will be frozen.
    val transform = remember(zoom, panX, panY) {
        dev.hryshyn.remanence.ui.collage.CropTransform(zoom.toDouble(), panX.toDouble(), panY.toDouble())
    }
    val retained = remember(base, transform) {
        FixedFrameCropCorrection.sourceWindow(base, transform) ?: base
    }
    val safeAspect = if (slotAspect.isFinite() && slotAspect > 0f) slotAspect else 1f

    Column(modifier = modifier.fillMaxWidth().testTag("crop_stage")) {
        Text(
            androidx.compose.ui.res.stringResource(R.string.hold_crop_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("crop_stage_label"),
        )
        Text(
            androidx.compose.ui.res.stringResource(R.string.hold_crop_stage_hint),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("crop_stage_hint"),
        )
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(safeAspect)
                // `.crop-window{overflow:hidden}`: the frame edge is the
                // retained window, everything else is clipped away.
                .clipToBounds()
                .background(Color(0xFFD4D3DC))
                .semantics { stateDescription = windowState(retained) }
                .testTag("crop_frame"),
        ) {
            val frameWidth = constraints.maxWidth.toFloat()
            val frameHeight = constraints.maxHeight.toFloat()
            val sourceWidth = source.widthPx.coerceAtLeast(1).toFloat()
            val sourceHeight = source.heightPx.coerceAtLeast(1).toFloat()
            val baseWidth = base.width.coerceAtLeast(1e-9)
            val baseHeight = base.height.coerceAtLeast(1e-9)
            if (bitmap != null && frameWidth > 0f && frameHeight > 0f) {
                // The approved excerpt fills the frame (frozen `excerpt()`):
                // image size = frame / base fraction, offset = -base origin.
                val imageWidth = (frameWidth / baseWidth).toDouble()
                val imageHeight = (frameHeight / baseHeight).toDouble()
                val offsetX = -base.x * imageWidth
                val offsetY = -base.y * imageHeight
                val density = LocalDensity.current
                // `.fragment`: fills the window and carries the frozen
                // translate(x%,y%) scale(z) about the centre, percent of frame.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = panX / 100f * frameWidth
                            translationY = panY / 100f * frameHeight
                        }
                        .testTag("crop_fragment"),
                ) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "crop source",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .offset(
                                x = with(density) { offsetX.toFloat().toDp() },
                                y = with(density) { offsetY.toFloat().toDp() },
                            )
                            // `requiredSize` (not `size`): like the frozen
                            // absolutely-positioned `.fragment img`, the
                            // excerpt may be LARGER than the window — plain
                            // `size` would be coerced into the frame's
                            // measured constraints and never overflow.
                            .requiredSize(
                                width = with(density) { imageWidth.toFloat().toDp() },
                                height = with(density) { imageHeight.toFloat().toDp() },
                            )
                            .testTag("crop_image"),
                    )
                }
            }
            // `.crop-grid`: border + thirds, absolute over the fragment.
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .semantics { contentDescription = "crop grid" }
                    .testTag("crop_grid"),
            ) {
                val borderWidth = 2.dp.toPx()
                val thirdW = size.width / 3f
                val thirdH = size.height / 3f
                val lineColor = Color.White.copy(alpha = 0.33f)
                drawLine(
                    color = lineColor,
                    start = Offset(thirdW, 0f),
                    end = Offset(thirdW, size.height),
                    strokeWidth = borderWidth,
                )
                drawLine(
                    color = lineColor,
                    start = Offset(thirdW * 2f, 0f),
                    end = Offset(thirdW * 2f, size.height),
                    strokeWidth = borderWidth,
                )
                drawLine(
                    color = lineColor,
                    start = Offset(0f, thirdH),
                    end = Offset(size.width, thirdH),
                    strokeWidth = borderWidth,
                )
                drawLine(
                    color = lineColor,
                    start = Offset(0f, thirdH * 2f),
                    end = Offset(size.width, thirdH * 2f),
                    strokeWidth = borderWidth,
                )
                drawRect(
                    color = Color.White,
                    topLeft = Offset.Zero,
                    size = Size(size.width, size.height),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = borderWidth),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Zoom", modifier = Modifier.testTag("crop_zoom_label"))
        Slider(
            value = zoom,
            onValueChange = { onUpdate(it, panX, panY) },
            valueRange = 1f..1.25f,
            steps = 24,
            modifier = Modifier.fillMaxWidth().testTag("crop_zoom"),
        )
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            // Direction deltas come from the accepted T157 contract
            // (engine.mjs:118 pan(dir): one axis, ±1 frame percent); the
            // ViewModel clamps the sum through clampTransform.
            CropPadButton("←", "crop_pan_left") {
                val d = FixedFrameCropCorrection.panDelta(PanDirection.LEFT)
                onUpdate(zoom, panX + d.xPercent.toFloat(), panY + d.yPercent.toFloat())
            }
            CropPadButton("↑", "crop_pan_up") {
                val d = FixedFrameCropCorrection.panDelta(PanDirection.UP)
                onUpdate(zoom, panX + d.xPercent.toFloat(), panY + d.yPercent.toFloat())
            }
            CropPadButton("↓", "crop_pan_down") {
                val d = FixedFrameCropCorrection.panDelta(PanDirection.DOWN)
                onUpdate(zoom, panX + d.xPercent.toFloat(), panY + d.yPercent.toFloat())
            }
            CropPadButton("→", "crop_pan_right") {
                val d = FixedFrameCropCorrection.panDelta(PanDirection.RIGHT)
                onUpdate(zoom, panX + d.xPercent.toFloat(), panY + d.yPercent.toFloat())
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            dev.hryshyn.remanence.ui.hold.HoldTextButton(
                onClick = onBack,
                modifier = Modifier.testTag("crop_back"),
            ) { Text(androidx.compose.ui.res.stringResource(R.string.hold_back)) }
            dev.hryshyn.remanence.ui.hold.HoldTextButton(
                onClick = onReset,
                modifier = Modifier.testTag("crop_reset"),
            ) { Text("Reset") }
            dev.hryshyn.remanence.ui.hold.HoldButton(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().testTag("crop_done"),
            ) { Text("Done") }
        }
    }
}

/** Stable, machine-readable rendering of the retained source window. */
internal fun windowState(window: NormalizedCrop): String = String.format(
    Locale.ROOT,
    "window %.6f,%.6f,%.6f,%.6f",
    window.x,
    window.y,
    window.width,
    window.height,
)

@Composable
private fun CropPadButton(label: String, tag: String, onClick: () -> Unit) {
    dev.hryshyn.remanence.ui.hold.HoldTextButton(
        onClick = onClick,
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .testTag(tag),
    ) { Text(label) }
}
