package dev.hryshyn.remanence.ui.create

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.CollageCanvas
import dev.hryshyn.remanence.ui.collage.CollageRenderer
import dev.hryshyn.remanence.ui.collage.CollageTextMeasurer
import dev.hryshyn.remanence.ui.collage.rememberCollageTextMeasurer

/**
 * T150 photo hotspots (Astra `render.mjs:11` capsule-slot buttons): the
 * shared [CollageCanvas] with one invisible tap target per placement.
 * Targets cover exactly their slot rect (no grown invisible rectangles
 * across neighboring photos); tapping one opens the crop stage for its
 * content id. Fractions come from the placement's own canvas units, so
 * no renderer layout is duplicated here.
 */
@Composable
internal fun CollageHotspotCanvas(
    scene: CollageRenderer.Scene,
    images: Map<String, ImageBitmap>,
    placements: List<GeneratorExpression.Placement>,
    onSlotTap: (String) -> Unit,
    modifier: Modifier = Modifier,
    textMeasurer: CollageTextMeasurer = rememberCollageTextMeasurer(),
    artwork: ImageBitmap? = null,
    onMusicClick: (() -> Unit)? = null,
    musicActionLabel: String? = null,
) {
    BoxWithConstraints(
        modifier.aspectRatio(scene.canvasWidth.toFloat() / scene.canvasHeight.toFloat()),
    ) {
        CollageCanvas(
            scene = scene,
            images = images,
            contentDescription = stringResource(R.string.media_collage_description),
            modifier = Modifier.fillMaxSize(),
            textMeasurer = textMeasurer,
            artwork = artwork,
            onMusicClick = onMusicClick,
            musicActionLabel = musicActionLabel,
        )
        val width = maxWidth
        val height = maxHeight
        val sceneWidth = scene.canvasWidth.toFloat()
        val sceneHeight = scene.canvasHeight.toFloat()
        if (sceneWidth <= 0f || sceneHeight <= 0f) return@BoxWithConstraints
        placements.forEachIndexed { index, placement ->
            val label = stringResource(R.string.media_crop_photo, index + 1, placements.size)
            Box(
                Modifier
                    .offset(
                        x = width * placement.x.toFloat() / sceneWidth,
                        y = height * placement.y.toFloat() / sceneHeight,
                    )
                    .size(
                        width = width * placement.width.toFloat() / sceneWidth,
                        height = height * placement.height.toFloat() / sceneHeight,
                    )
                    .clickable(
                        role = Role.Button,
                        onClickLabel = label,
                    ) { onSlotTap(placement.contentId) }
                    .semantics { contentDescription = label }
                    .testTag("crop_hotspot_$index"),
            )
        }
    }
}
