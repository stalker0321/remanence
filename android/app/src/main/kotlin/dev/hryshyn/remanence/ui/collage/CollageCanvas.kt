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
            softWrap = request.softWrap,
            maxLines = if (request.softWrap) Int.MAX_VALUE else 1,
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
     * The two nowrap spine runs measured exactly like
     * [CollageRenderer.prepare] measures them: the label at the sealed
     * music face and the artist at the frozen 11px track face, each one
     * line inside the shared spine budgets. Null only for a blank field
     * ([dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1] requires
     * both fields, so production scenes always carry both).
     */
    data class SpineCopyLayouts(
        val title: TextLayoutResult?,
        val artist: TextLayoutResult?,
    )

    /**
     * Layout for a vertical spine music copy: two separate nowrap runs
     * (`.g6 .music-spine .music-label{white-space:nowrap}` plus the
     * `.music .track` node), reading down the band
     * (`.music-copy{writing-mode:vertical-rl}`), constrained by the same
     * budgets [CollageRenderer.spineCopyFit] gates with — never the joined
     * two-line run that always overflowed the one-line cap.
     */
    fun layoutSpine(music: CollageRenderer.SceneMusic, grammar: String): SpineCopyLayouts {
        val band = V5Decor.bandLayout(grammar, "spine", music.region.height)
        val reading = CollageRenderer.spineReadingBudget(music.region, band).coerceAtLeast(0)
        val cross = CollageRenderer.spineCrossBudget(music.region, band).coerceAtLeast(0)
        return SpineCopyLayouts(
            title = music.title.takeIf { it.isNotBlank() }?.let {
                measureSpineRun(
                    text = it,
                    family = music.family,
                    size = music.size,
                    weight = music.weight,
                    italic = music.italic,
                    line = music.line,
                    fontResourceId = music.fontResourceId,
                    reading = reading,
                    cross = cross,
                )
            },
            artist = music.artist.takeIf { it.isNotBlank() }?.let {
                measureSpineRun(
                    text = it,
                    family = CollageRenderer.SPINE_ARTIST_FAMILY,
                    size = CollageRenderer.SPINE_ARTIST_SIZE,
                    weight = CollageRenderer.SPINE_ARTIST_WEIGHT,
                    italic = CollageRenderer.SPINE_ARTIST_ITALIC,
                    line = CollageRenderer.SPINE_ARTIST_LINE,
                    fontResourceId = FontRegistry.resourceFor(
                        CollageRenderer.SPINE_ARTIST_FAMILY,
                        CollageRenderer.SPINE_ARTIST_WEIGHT,
                        CollageRenderer.SPINE_ARTIST_ITALIC,
                    ) ?: error(
                        "unbundled collage face " +
                            "${CollageRenderer.SPINE_ARTIST_FAMILY}/" +
                            "${CollageRenderer.SPINE_ARTIST_WEIGHT}/false",
                    ),
                    reading = reading,
                    cross = cross,
                )
            },
        )
    }

    private fun measureSpineRun(
        text: String,
        family: String,
        size: Int,
        weight: Int,
        italic: Boolean,
        line: Int,
        fontResourceId: Int,
        reading: Int,
        cross: Int,
    ): TextLayoutResult {
        val fontWeight = FontWeight(weight)
        val fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal
        val style = TextStyle(
            fontFamily = FontFamily(Font(fontResourceId, fontWeight, fontStyle)),
            fontSize = size.sp,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            lineHeight = line.sp,
            textAlign = TextAlign.Start,
        )
        return measurer.measure(
            text = AnnotatedString(text),
            style = style,
            overflow = TextOverflow.Clip,
            softWrap = false,
            maxLines = 1,
            constraints = Constraints(maxWidth = reading, maxHeight = cross),
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
    val musicLayout = music?.takeIf { !it.vertical }?.let {
        remember(it, textMeasurer) { textMeasurer.layout(it.toMeasureRequest()) }
    }
    val spineLayouts = music?.takeIf { it.vertical }?.let {
        remember(it, textMeasurer) { textMeasurer.layoutSpine(it, scene.grammarId) }
    }
    val provenanceLayouts = remember(scene.provenance, textMeasurer) {
        scene.provenance.map { textMeasurer.layoutProvenance(it) }
    }

    val musicFits = when {
        music == null -> true
        music.vertical -> spineLayouts?.let { fitsSpine(music, scene.grammarId, it) } == true
        else -> musicLayout?.fitsRegion(music.region) == true
    }
    val drawable = drawOps != null &&
        (note == null || noteLayout?.fitsRegion(note.region) == true) &&
        musicFits &&
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
                                // NOTE: `decor.shadow` (exact 2/4/8px
                                // `#151f2330` print-plane shadow) is carried
                                // on the op but not painted here: the public
                                // Compose `Canvas` in this BOM has no shadow
                                // API (`nativeCanvas` is absent), and
                                // framework shadow layers are ignored on
                                // hardware canvases. Fill + edge border below
                                // are exact.
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
                                        style = Stroke(width = decor.borderWidth.toFloat()),
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
                                        // g11 interlock uses the asymmetric
                                        // 4/26/4/4 radii; every other grammar
                                        // uses 12% per axis (== the frozen
                                        // safety mask, so clip and crop agree).
                                        val radii = V5Decor.roundRadiiPx(scene.grammarId, dst.width, dst.height)
                                        val path = Path().apply {
                                            addRoundRect(
                                                RoundRect(
                                                    left = dst.x.toFloat(),
                                                    top = dst.y.toFloat(),
                                                    right = (dst.x + dst.width).toFloat(),
                                                    bottom = (dst.y + dst.height).toFloat(),
                                                    topLeftCornerRadius = CornerRadius(radii[0], radii[1]),
                                                    topRightCornerRadius = CornerRadius(radii[2], radii[3]),
                                                    bottomRightCornerRadius = CornerRadius(radii[4], radii[5]),
                                                    bottomLeftCornerRadius = CornerRadius(radii[6], radii[7]),
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
                                val track = scene.music
                                if (track?.vertical == true) {
                                    // Spine copy reads vertically
                                    // (`.g6 .music-spine .music-copy{
                                    // writing-mode:vertical-rl}`): each
                                    // nowrap run gets its own +90° pass at
                                    // the shared [CollageRenderer.spineCopyPlacements]
                                    // anchors (title, 6px copy gap, artist),
                                    // so prepare, the canvas fit gate, and
                                    // these pixels use one geometry.
                                    val runs = spineLayouts ?: return@Canvas
                                    val title = runs.title
                                    val artist = runs.artist
                                    if (title == null && artist == null) return@Canvas
                                    val band = V5Decor.bandLayout(
                                        scene.grammarId,
                                        track.mode,
                                        track.region.height,
                                    )
                                    val place = CollageRenderer.spineCopyPlacements(
                                        region = track.region,
                                        band = band,
                                        titleAdvance = title?.size?.width ?: 0,
                                        titleLineBox = title?.size?.height ?: 0,
                                        artistLineBox = artist?.size?.height ?: 0,
                                    )
                                    for ((run, at) in listOfNotNull(
                                        title?.let { it to place.title },
                                        artist?.let { it to place.artist },
                                    )) {
                                        val layout = run
                                        withTransform({
                                            translate(at.right.toFloat(), at.top.toFloat())
                                            rotate(90f, pivot = Offset.Zero)
                                        }) {
                                            drawText(
                                                textLayoutResult = layout,
                                                color = Color(op.color),
                                                topLeft = Offset.Zero,
                                            )
                                        }
                                    }
                                    return@forEach
                                }
                                val layout = musicLayout ?: return@Canvas
                                // V4 cover + text composition: the cover box
                                // and copy rect come from the shared exact
                                // helper (same rects prepare measured), so
                                // pixels and gate agree. Legacy scenes keep
                                // the full-region layout.
                                val cover = scene.artwork
                                if (cover != null && scene.music != null) {
                                    val placement = V5Decor.placeCover(
                                        op.region.x, op.region.y,
                                        op.region.width, op.region.height,
                                        scene.grammarId, scene.music.mode,
                                    ) ?: return@Canvas
                                    val text = placement.text
                                    clipRect(
                                        left = text.x.toFloat(),
                                        top = text.y.toFloat(),
                                        right = (text.x + text.width).toFloat(),
                                        bottom = (text.y + text.height).toFloat(),
                                    ) {
                                        drawText(
                                            textLayoutResult = layout,
                                            color = Color(op.color),
                                            topLeft = Offset(text.x.toFloat(), text.y.toFloat()),
                                        )
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
                                val track = scene.music
                                if (track == null) {
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
                                    val placement = V5Decor.placeCover(
                                        coverRegion.x, coverRegion.y,
                                        coverRegion.width, coverRegion.height,
                                        scene.grammarId, track.mode,
                                    ) ?: return@Canvas
                                    val box = placement.cover
                                    if (placement.fit == V5Decor.CoverFit.COVER) {
                                        val scale = maxOf(
                                            box.width.toFloat() / image.width,
                                            box.height.toFloat() / image.height,
                                        )
                                        val srcW = (box.width / scale).roundToInt().coerceAtMost(image.width)
                                        val srcH = (box.height / scale).roundToInt().coerceAtMost(image.height)
                                        if (srcW < 1 || srcH < 1) return@Canvas
                                        drawImage(
                                            image = image,
                                            srcOffset = IntOffset(
                                                (image.width - srcW) / 2,
                                                (image.height - srcH) / 2,
                                            ),
                                            srcSize = IntSize(srcW, srcH),
                                            dstOffset = IntOffset(box.x, box.y),
                                            dstSize = IntSize(box.width, box.height),
                                            filterQuality = FilterQuality.High,
                                        )
                                    } else {
                                        val scale = min(
                                            box.width.toFloat() / image.width,
                                            box.height.toFloat() / image.height,
                                        )
                                        val width = (image.width * scale).roundToInt()
                                        val height = (image.height * scale).roundToInt()
                                        if (width < 1 || height < 1) return@Canvas
                                        drawImage(
                                            image = image,
                                            srcOffset = IntOffset.Zero,
                                            srcSize = IntSize(image.width, image.height),
                                            dstOffset = IntOffset(
                                                box.x + (box.width - width) / 2,
                                                box.y + (box.height - height) / 2,
                                            ),
                                            dstSize = IntSize(width, height),
                                            filterQuality = FilterQuality.High,
                                        )
                                    }
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

private fun fitsSpine(
    music: CollageRenderer.SceneMusic,
    grammar: String,
    layouts: CollageTextMeasurer.SpineCopyLayouts,
): Boolean {
    // The canvas gate is the SAME decision as prepare: same runs, same
    // frozen budgets ([CollageRenderer.spineCopyFit]).
    val band = V5Decor.bandLayout(grammar, "spine", music.region.height)
    return CollageRenderer.spineCopyFit(
        region = music.region,
        band = band,
        title = layouts.title?.asMeasurement(),
        artist = layouts.artist?.asMeasurement(),
    ).fits
}

private fun TextLayoutResult.asMeasurement() = CollageRenderer.TextMeasurement(
    width = size.width,
    height = size.height,
    hasVisualOverflow = hasVisualOverflow,
)

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
