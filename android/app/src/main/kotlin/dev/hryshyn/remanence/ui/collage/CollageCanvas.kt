package dev.hryshyn.remanence.ui.collage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
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
import androidx.compose.ui.text.style.TextAlign
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

    /**
     * Layout for one authored provenance mark: the sealed face with brand
     * tracking (`tracking * size` sp; sender 0). Constraints are the long
     * side (reading direction) by the short side, matching
     * [CollageRenderer.prepare].
     */
    fun layoutProvenance(mark: CollageRenderer.SceneProvenance): TextLayoutResult {
        val weight = FontWeight(mark.weight)
        val fontStyle = if (mark.italic) FontStyle.Italic else FontStyle.Normal
        val horizontal = mark.orientation ==
            dev.hryshyn.remanence.core.model.GeneratorExpression.MarkOrientation.HORIZONTAL
        val align = when (mark.align) {
            dev.hryshyn.remanence.core.model.GeneratorExpression.MarkAlign.CENTER -> TextAlign.Center
            dev.hryshyn.remanence.core.model.GeneratorExpression.MarkAlign.RIGHT -> TextAlign.Right
            else -> TextAlign.Left
        }
        val style = TextStyle(
            fontFamily = FontFamily(
                Font(mark.fontResourceId, weight, fontStyle),
            ),
            fontSize = mark.size.sp,
            fontWeight = weight,
            fontStyle = fontStyle,
            lineHeight = mark.line.sp,
            letterSpacing = (mark.tracking * mark.size).sp,
            textAlign = align,
        )
        return measurer.measure(
            text = AnnotatedString(mark.text),
            style = style,
            overflow = TextOverflow.Clip,
            softWrap = false,
            maxLines = 1,
            constraints = Constraints(
                maxWidth = if (horizontal) mark.region.width else mark.region.height,
                maxHeight = if (horizontal) mark.region.height else mark.region.width,
            ),
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
 * [CollagePainter.LAYER_ORDER]: paper, decor (provisional kind-to-role
 * fills), photos (sealed source/destination rects with aperture clips:
 * round is a 12%-radius rounded rect per `crop.js` `maskPoint`, ellipse is
 * the inscribed ellipse), note clipped to its region, then music
 * background + title/artist (or the verified cover, or the v4 cover + text
 * composition), closed by the authored provenance marks with exact
 * vertical rotation and alignment.
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
    val provenanceLayouts = remember(scene.provenance, textMeasurer) {
        scene.provenance.map { textMeasurer.layoutProvenance(it) }
    }

    val drawable = drawOps != null &&
        (note == null || noteLayout?.fitsRegion(note.region) == true) &&
        (music == null || musicLayout?.fitsRegion(music.region) == true) &&
        (scene.artwork == null || artwork != null) &&
        scene.provenance.zip(provenanceLayouts).all { (mark, layout) -> layout.fitsProvenance(mark) }

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

                            is CollagePainter.Op.Decor -> {
                                val decor = scene.decor[op.index]
                                drawRect(
                                    color = Color(decor.background),
                                    topLeft = Offset(decor.region.x.toFloat(), decor.region.y.toFloat()),
                                    size = Size(decor.region.width.toFloat(), decor.region.height.toFloat()),
                                )
                                if (decor.border != null) {
                                    drawRect(
                                        color = Color(decor.border),
                                        topLeft = Offset(decor.region.x.toFloat(), decor.region.y.toFloat()),
                                        size = Size(decor.region.width.toFloat(), decor.region.height.toFloat()),
                                        style = Stroke(width = 1f),
                                    )
                                }
                            }

                            is CollagePainter.Op.Photo -> {
                                val image = images.getValue(op.contentId)
                                val dst = op.destination
                                when (op.shape) {
                                    dev.hryshyn.remanence.core.model.GeneratorExpression.PhotoShape.RECT ->
                                        drawImage(
                                            image = image,
                                            srcOffset = IntOffset(op.source.x, op.source.y),
                                            srcSize = IntSize(op.source.width, op.source.height),
                                            dstOffset = IntOffset(dst.x, dst.y),
                                            dstSize = IntSize(dst.width, dst.height),
                                            filterQuality = FilterQuality.High,
                                        )
                                    dev.hryshyn.remanence.core.model.GeneratorExpression.PhotoShape.ROUND -> {
                                        val path = Path().apply {
                                            addRoundRect(
                                                RoundRect(
                                                    left = dst.x.toFloat(),
                                                    top = dst.y.toFloat(),
                                                    right = (dst.x + dst.width).toFloat(),
                                                    bottom = (dst.y + dst.height).toFloat(),
                                                    cornerRadius = CornerRadius(
                                                        V5Decor.ROUND_CORNER_FRACTION.toFloat() * dst.width,
                                                        V5Decor.ROUND_CORNER_FRACTION.toFloat() * dst.height,
                                                    ),
                                                ),
                                            )
                                        }
                                        clipPath(path) {
                                            drawImage(
                                                image = image,
                                                srcOffset = IntOffset(op.source.x, op.source.y),
                                                srcSize = IntSize(op.source.width, op.source.height),
                                                dstOffset = IntOffset(dst.x, dst.y),
                                                dstSize = IntSize(dst.width, dst.height),
                                                filterQuality = FilterQuality.High,
                                            )
                                        }
                                    }
                                    dev.hryshyn.remanence.core.model.GeneratorExpression.PhotoShape.ELLIPSE -> {
                                        val path = Path().apply {
                                            addOval(
                                                Rect(
                                                    left = dst.x.toFloat(),
                                                    top = dst.y.toFloat(),
                                                    right = (dst.x + dst.width).toFloat(),
                                                    bottom = (dst.y + dst.height).toFloat(),
                                                ),
                                            )
                                        }
                                        clipPath(path) {
                                            drawImage(
                                                image = image,
                                                srcOffset = IntOffset(op.source.x, op.source.y),
                                                srcSize = IntSize(op.source.width, op.source.height),
                                                dstOffset = IntOffset(dst.x, dst.y),
                                                dstSize = IntSize(dst.width, dst.height),
                                                filterQuality = FilterQuality.High,
                                            )
                                        }
                                    }
                                }
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

                            is CollagePainter.Op.MusicBackground -> if (op.transparent) {
                                drawLine(
                                    color = Color(op.ink),
                                    start = Offset(op.region.x.toFloat(), op.region.y.toFloat()),
                                    end = Offset(
                                        (op.region.x + op.region.width).toFloat(),
                                        op.region.y.toFloat(),
                                    ),
                                    strokeWidth = 1f,
                                )
                            } else {
                                drawRect(
                                    color = Color(op.color),
                                    topLeft = Offset(op.region.x.toFloat(), op.region.y.toFloat()),
                                    size = Size(op.region.width.toFloat(), op.region.height.toFloat()),
                                )
                            }

                            is CollagePainter.Op.MusicText -> {
                                val layout = musicLayout ?: return@Canvas
                                // V4 cover + text composition: the cover
                                // occupies the leading square (row) or the top
                                // square (narrow/column regions); the words
                                // take the remainder. This subdivision is a
                                // Canvas presentation projection: the sealed
                                // region stays exact and prepare measured the
                                // full region, so the remainder clip is honest.
                                val cover = scene.artwork
                                if (cover != null && scene.music != null) {
                                    val region = op.region
                                    val side = min(region.width, region.height)
                                    val gap = 9
                                    if (region.width > region.height) {
                                        val textX = region.x + side + gap
                                        val textW = region.x + region.width - textX
                                        if (textW >= 1) {
                                            clipRect(
                                                left = textX.toFloat(),
                                                top = region.y.toFloat(),
                                                right = (region.x + region.width).toFloat(),
                                                bottom = (region.y + region.height).toFloat(),
                                            ) {
                                                drawText(
                                                    textLayoutResult = layout,
                                                    color = Color(op.color),
                                                    topLeft = Offset(textX.toFloat(), region.y.toFloat()),
                                                )
                                            }
                                        }
                                    } else {
                                        val textY = region.y + side + gap
                                        val textH = region.y + region.height - textY
                                        if (textH >= 1) {
                                            clipRect(
                                                left = region.x.toFloat(),
                                                top = textY.toFloat(),
                                                right = (region.x + region.width).toFloat(),
                                                bottom = (region.y + region.height).toFloat(),
                                            ) {
                                                drawText(
                                                    textLayoutResult = layout,
                                                    color = Color(op.color),
                                                    topLeft = Offset(region.x.toFloat(), textY.toFloat()),
                                                )
                                            }
                                        }
                                    }
                                } else {
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
                            }

                            is CollagePainter.Op.MusicArtwork -> {
                                val coverRegion = op.region
                                val composed = scene.music != null
                                if (!composed) {
                                    drawRect(
                                        color = Color(op.background),
                                        topLeft = Offset(coverRegion.x.toFloat(), coverRegion.y.toFloat()),
                                        size = Size(coverRegion.width.toFloat(), coverRegion.height.toFloat()),
                                    )
                                    val image = artwork ?: return@Canvas
                                    val scale = min(
                                        coverRegion.width.toFloat() / image.width,
                                        coverRegion.height.toFloat() / image.height,
                                    )
                                    val width = (image.width * scale).roundToInt()
                                    val height = (image.height * scale).roundToInt()
                                    if (width < 1 || height < 1) return@Canvas
                                    drawImage(
                                        image = image,
                                        srcOffset = IntOffset.Zero,
                                        srcSize = IntSize(image.width, image.height),
                                        dstOffset = IntOffset(
                                            coverRegion.x + (coverRegion.width - width) / 2,
                                            coverRegion.y + (coverRegion.height - height) / 2,
                                        ),
                                        dstSize = IntSize(width, height),
                                        filterQuality = FilterQuality.High,
                                    )
                                } else {
                                    drawRect(
                                        color = Color(op.background),
                                        topLeft = Offset(coverRegion.x.toFloat(), coverRegion.y.toFloat()),
                                        size = Size(coverRegion.width.toFloat(), coverRegion.height.toFloat()),
                                    )
                                    val image = artwork ?: return@Canvas
                                    val side = min(coverRegion.width, coverRegion.height)
                                    if (side < 1) return@Canvas
                                    val dstX: Int
                                    val dstY: Int
                                    if (coverRegion.width > coverRegion.height) {
                                        dstX = coverRegion.x
                                        dstY = coverRegion.y + (coverRegion.height - side) / 2
                                    } else {
                                        dstX = coverRegion.x + (coverRegion.width - side) / 2
                                        dstY = coverRegion.y
                                    }
                                    val scale = min(
                                        side.toFloat() / image.width,
                                        side.toFloat() / image.height,
                                    )
                                    val width = (image.width * scale).roundToInt()
                                    val height = (image.height * scale).roundToInt()
                                    if (width < 1 || height < 1) return@Canvas
                                    drawImage(
                                        image = image,
                                        srcOffset = IntOffset.Zero,
                                        srcSize = IntSize(image.width, image.height),
                                        dstOffset = IntOffset(
                                            dstX + (side - width) / 2,
                                            dstY + (side - height) / 2,
                                        ),
                                        dstSize = IntSize(width, height),
                                        filterQuality = FilterQuality.High,
                                    )
                                }
                            }

                            is CollagePainter.Op.Provenance -> {
                                val mark = scene.provenance[op.index]
                                val layout = provenanceLayouts[op.index]
                                // Exact port of `grammars.js` render:
                                // vertical-up lays (h × w) switched, origin
                                // translated down by h, rotated -90°;
                                // vertical-down lays switched, origin
                                // translated right by w, rotated +90°.
                                when (mark.orientation) {
                                    dev.hryshyn.remanence.core.model.GeneratorExpression.MarkOrientation.HORIZONTAL ->
                                        clipRect(
                                            left = mark.region.x.toFloat(),
                                            top = mark.region.y.toFloat(),
                                            right = (mark.region.x + mark.region.width).toFloat(),
                                            bottom = (mark.region.y + mark.region.height).toFloat(),
                                        ) {
                                            drawText(
                                                textLayoutResult = layout,
                                                color = Color(mark.color),
                                                topLeft = Offset(
                                                    mark.region.x.toFloat(),
                                                    mark.region.y.toFloat(),
                                                ),
                                            )
                                        }
                                    dev.hryshyn.remanence.core.model.GeneratorExpression.MarkOrientation.VERTICAL_UP ->
                                        withTransform({
                                            translate(
                                                mark.region.x.toFloat(),
                                                (mark.region.y + mark.region.height).toFloat(),
                                            )
                                            rotate(-90f, pivot = Offset.Zero)
                                        }) {
                                            drawText(
                                                textLayoutResult = layout,
                                                color = Color(mark.color),
                                                topLeft = Offset.Zero,
                                            )
                                        }
                                    dev.hryshyn.remanence.core.model.GeneratorExpression.MarkOrientation.VERTICAL_DOWN ->
                                        withTransform({
                                            translate(
                                                (mark.region.x + mark.region.width).toFloat(),
                                                mark.region.y.toFloat(),
                                            )
                                            rotate(90f, pivot = Offset.Zero)
                                        }) {
                                            drawText(
                                                textLayoutResult = layout,
                                                color = Color(mark.color),
                                                topLeft = Offset.Zero,
                                            )
                                        }
                                }
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

private fun TextLayoutResult.fitsProvenance(mark: CollageRenderer.SceneProvenance): Boolean {
    if (hasVisualOverflow) return false
    val horizontal = mark.orientation ==
        dev.hryshyn.remanence.core.model.GeneratorExpression.MarkOrientation.HORIZONTAL
    val maxWidth = if (horizontal) mark.region.width else mark.region.height
    val maxHeight = if (horizontal) mark.region.height else mark.region.width
    return size.width in 1..maxWidth && size.height in 1..maxHeight
}

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
