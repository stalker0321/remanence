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
            contentDescription = "collage candidate",
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
                        onClickLabel = "adjust crop",
                    ) { onSlotTap(placement.contentId) }
                    .testTag("crop_hotspot_$index"),
            )
        }
    }
}
