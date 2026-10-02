package dev.hryshyn.remanence.ui.create

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.ui.create.CreateViewModel.CropCorrection
import dev.hryshyn.remanence.ui.collage.FixedFrameCropCorrection
import dev.hryshyn.remanence.ui.collage.PanDirection
import dev.hryshyn.remanence.R

/**
 * T150 crop seam surfaces (Astra guide rows 29/41): per-photo fixed-frame
 * correction entry plus the crop stage. The stage edits DISPLAY values
 * only (zoom 1..1.25, pan bounded by (zoom-1)*50, four direction buttons);
 * sealed geometry is untouched until T154 consumes [CropCorrection] through
 * the model/renderer API under T155's contract. Absent entry == identity.
 */
@Composable
fun CropSlotPicker(
    sources: List<GeneratorPreviewSource>,
    corrections: Map<String, CropCorrection>,
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

@Composable
fun CropStageContent(
    source: GeneratorPreviewSource,
    correction: CropCorrection?,
    onUpdate: (zoom: Float, panX: Float, panY: Float) -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(source) {
        BitmapFactory.decodeByteArray(source.jpegBytes, 0, source.jpegBytes.size)?.asImageBitmap()
    }
    val zoom = correction?.zoom ?: 1f
    val panX = correction?.panX ?: 0f
    val panY = correction?.panY ?: 0f
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
                .aspectRatio(source.widthPx.toFloat() / source.heightPx.toFloat())
                .testTag("crop_frame"),
        ) {
            val widthPx = constraints.maxWidth.toFloat()
            val heightPx = constraints.maxHeight.toFloat()
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "crop source",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = panX / 100f * widthPx
                            translationY = panY / 100f * heightPx
                        }
                        .testTag("crop_image"),
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

@Composable
private fun CropPadButton(label: String, tag: String, onClick: () -> Unit) {
    dev.hryshyn.remanence.ui.hold.HoldTextButton(
        onClick = onClick,
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .testTag(tag),
    ) { Text(label) }
}
