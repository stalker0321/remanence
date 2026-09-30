package dev.hryshyn.remanence.ui.collage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Compose-backed text measurement for the collage contract. Built at
 * [Density] 1 so measured units equal sealed reference-canvas pixels, and the
 * exact same instance can be handed to [CollageRenderer.prepare] (as a
 * [CollageRenderer.TextMeasurer]) and used here to draw — the layout that was
 * accepted is the layout that is drawn. Faces resolve only through
 * [FontRegistry]; there is no fallback font.
 */
class CollageTextMeasurer(
    private val measurer: TextMeasurer,
) : CollageRenderer.TextMeasurer {

    fun layout(request: CollageRenderer.TextMeasureRequest): TextLayoutResult {
        val resourceId = FontRegistry.resourceFor(request.family, request.weight, request.italic)
            ?: error("unbundled collage face ${request.family}/${request.weight}/${request.italic}")
        val weight = FontWeight(request.weight)
        val fontStyle = if (request.italic) FontStyle.Italic else FontStyle.Normal
        val style = TextStyle(
            fontFamily = FontFamily(Font(resourceId, weight, fontStyle)),
            fontSize = request.size.sp,
            fontWeight = weight,
            fontStyle = fontStyle,
            lineHeight = request.line.sp,
        )
        return measurer.measure(
            text = AnnotatedString(request.text),
            style = style,
            overflow = TextOverflow.Clip,
            softWrap = true,
            maxLines = Int.MAX_VALUE,
            constraints = Constraints(maxWidth = request.maxWidth, maxHeight = request.maxHeight),
        )
    }

    override fun measure(request: CollageRenderer.TextMeasureRequest): CollageRenderer.TextMeasurement {
        val layout = layout(request)
        return CollageRenderer.TextMeasurement(
            width = layout.size.width,
            height = layout.size.height,
            hasVisualOverflow = layout.hasVisualOverflow,
        )
    }
}

/** Canvas-scale measurer shared by prepare and paint (density 1, canvas units). */
@Composable
fun rememberCollageTextMeasurer(): CollageTextMeasurer {
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val layoutDirection = LocalLayoutDirection.current
    return remember(fontFamilyResolver, layoutDirection) {
        CollageTextMeasurer(TextMeasurer(fontFamilyResolver, Density(1f, 1f), layoutDirection, 8))
    }
}

/**
 * Paints an already-[CollageRenderer.Scene]-Ready collage with caller-owned
 * in-memory decoded images, on the fixed reference canvas uniformly scaled and
 * centered in [Modifier]'s bounds.
 *
 * Fail-closed: missing/mismatched image ids, a non-positive transform, or a
 * text layout that does not fit its sealed region draw **no** content at all —
 * never a partial sheet, shrink, reflow, or dropped line. Draw order is
 * [CollagePainter.LAYER_ORDER]: paper, photos (sealed source/destination
 * rects), note clipped to its region, then music background + title/artist (or
 * the verified cover) clipped to the music region.
 *
 * The whole composition is one accessibility node: [contentDescription] and
 * [Role.Image]. Internal layers are not separate accessibility nodes.
 */
@Composable
fun CollageCanvas(
    scene: CollageRenderer.Scene,
    images: Map<String, ImageBitmap>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    textMeasurer: CollageTextMeasurer = rememberCollageTextMeasurer(),
    /**
     * Verified thumbnail cover bitmap, or null. When the scene carries a
     * [CollageRenderer.SceneArtwork] this must be present; the canvas draws
     * nothing otherwise (fail-closed, never a placeholder).
     */
    artwork: ImageBitmap? = null,
) {
    val imageSizes = remember(images) {
        images.mapValues { CollageRenderer.SourcePhoto(it.value.width, it.value.height) }
    }
    val drawOps = remember(scene, imageSizes) { CollagePainter.drawList(scene, imageSizes) }
    val note = scene.note
    val music = scene.music
    val noteLayout = note?.let { remember(it, textMeasurer) { textMeasurer.layout(it.toMeasureRequest()) } }
    val musicLayout = music?.let { remember(it, textMeasurer) { textMeasurer.layout(it.toMeasureRequest()) } }

    val drawable = drawOps != null &&
        (note == null || noteLayout?.fitsRegion(note.region) == true) &&
        (music == null || musicLayout?.fitsRegion(music.region) == true) &&
        (scene.artwork == null || artwork != null)

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("collage_canvas")
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                role = Role.Image
            },
    ) {
        if (drawable) {
            val renderOps = drawOps
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("collage_canvas_content"),
            ) {
                val transform = CollagePainter.transform(
                    viewportWidth = size.width,
                    viewportHeight = size.height,
                    canvasWidth = scene.canvasWidth,
                    canvasHeight = scene.canvasHeight,
                ) ?: return@Canvas
                withTransform({
                    translate(transform.dx, transform.dy)
                    scale(transform.scale, transform.scale, pivot = Offset.Zero)
                }) {
                    renderOps.forEach { op ->
                        when (op) {
                            is CollagePainter.Op.Paper -> drawRect(
                                color = Color(op.color),
                                topLeft = Offset(op.rect.x.toFloat(), op.rect.y.toFloat()),
                                size = Size(op.rect.width.toFloat(), op.rect.height.toFloat()),
                            )

                            is CollagePainter.Op.Photo -> {
                                val image = images.getValue(op.contentId)
                                drawImage(
                                    image = image,
                                    srcOffset = IntOffset(op.source.x, op.source.y),
                                    srcSize = IntSize(op.source.width, op.source.height),
                                    dstOffset = IntOffset(op.destination.x, op.destination.y),
                                    dstSize = IntSize(op.destination.width, op.destination.height),
                                    filterQuality = FilterQuality.High,
                                )
                            }

                            is CollagePainter.Op.Note -> {
                                val layout = noteLayout ?: return@Canvas
                                clipRect(
                                    left = op.region.x.toFloat(),
                                    top = op.region.y.toFloat(),
                                    right = (op.region.x + op.region.width).toFloat(),
                                    bottom = (op.region.y + op.region.height).toFloat(),
                                ) {
                                    drawText(
                                        textLayoutResult = layout,
                                        color = Color(op.color),
                                        topLeft = Offset(op.region.x.toFloat(), op.region.y.toFloat()),
                                    )
                                }
                            }

                            is CollagePainter.Op.MusicBackground -> drawRect(
                                color = Color(op.color),
                                topLeft = Offset(op.region.x.toFloat(), op.region.y.toFloat()),
                                size = Size(op.region.width.toFloat(), op.region.height.toFloat()),
                            )

                            is CollagePainter.Op.MusicText -> {
                                val layout = musicLayout ?: return@Canvas
                                clipRect(
                                    left = op.region.x.toFloat(),
                                    top = op.region.y.toFloat(),
                                    right = (op.region.x + op.region.width).toFloat(),
                                    bottom = (op.region.y + op.region.height).toFloat(),
                                ) {
                                    drawText(
                                        textLayoutResult = layout,
                                        color = Color(op.color),
                                        topLeft = Offset(op.region.x.toFloat(), op.region.y.toFloat()),
                                    )
                                }
                            }

                            is CollagePainter.Op.MusicArtwork -> {
                                drawRect(
                                    color = Color(op.background),
                                    topLeft = Offset(op.region.x.toFloat(), op.region.y.toFloat()),
                                    size = Size(op.region.width.toFloat(), op.region.height.toFloat()),
                                )
                                val image = artwork ?: return@Canvas
                                val scale = min(
                                    op.region.width.toFloat() / image.width,
                                    op.region.height.toFloat() / image.height,
                                )
                                val width = (image.width * scale).roundToInt()
                                val height = (image.height * scale).roundToInt()
                                if (width < 1 || height < 1) return@Canvas
                                drawImage(
                                    image = image,
                                    srcOffset = IntOffset.Zero,
                                    srcSize = IntSize(image.width, image.height),
                                    dstOffset = IntOffset(
                                        op.region.x + (op.region.width - width) / 2,
                                        op.region.y + (op.region.height - height) / 2,
                                    ),
                                    dstSize = IntSize(width, height),
                                    filterQuality = FilterQuality.High,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun TextLayoutResult.fitsRegion(region: CollageRenderer.RectI): Boolean =
    !hasVisualOverflow && size.width in 1..region.width && size.height in 1..region.height

private fun CollageRenderer.SceneText.toMeasureRequest() = CollageRenderer.TextMeasureRequest(
    text = text,
    family = family,
    size = size,
    weight = weight,
    italic = italic,
    line = line,
    maxWidth = region.width,
    maxHeight = region.height,
)

private fun CollageRenderer.SceneMusic.toMeasureRequest() = CollageRenderer.TextMeasureRequest(
    text = text,
    family = family,
    size = size,
    weight = weight,
    italic = italic,
    line = line,
    maxWidth = region.width,
    maxHeight = region.height,
)
