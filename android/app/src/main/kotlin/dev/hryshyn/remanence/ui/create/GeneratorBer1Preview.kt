package dev.hryshyn.remanence.ui.create

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.sp
import dev.hryshyn.remanence.core.model.GeneratorEditorialRows
import dev.hryshyn.remanence.core.model.GeneratorExpression

/**
 * Stage1-C isolated BER1 Compose renderer seam (ADR-017 + G1 v2).
 *
 * Image part (safe/host-testable): [prepare] validates an ALREADY-VALIDATED
 * BER1 v2 [ResolvedExpression] and caller-owned in-memory [ImageBitmap]
 * sources, then exposes a draw plan built verbatim from the frozen
 * `placements`/`contentRect`; it never re-layouts, crops, omits or reorders.
 * Non-empty notes never yield [SceneResult.Ready] here; they yield
 * [SceneResult.NeedsNoteMeasure] carrying the photo scene so the Compose layer
 * can measure and draw.
 *
 * Note part (Compose-only): [GeneratorBer1Preview] lays out the FULL authored
 * note with bundled `hold_sans.ttf` (fontVersion 1) at the SAME reference
 * canvas scale used for drawing (density 1 so measurement units == canvas
 * units), constrained to the fixed [GeneratorEditorialRows.NOTE_REGION].
 * [NoteFit] decides fit from `hasVisualOverflow` + measured size, and the SAME
 * [TextLayoutResult] is then drawn, so a `Ready` note can never clip, ellipsize
 * or truncate. Measurement alone (any injected `Fits`) can never produce a
 * drawable note: the pure path has no measurement port and the note is only
 * accepted by real Compose measurement + the identical layout draw.
 *
 * DEVICE GATE (OPEN): the pixels themselves (font load, shaping across Android
 * text stacks, legibility/contrast) are not host-verifiable — there is no
 * pixel/golden tooling here. The fit/draw self-consistency is guaranteed by
 * construction; visual acceptance remains a device screenshot gate and is NOT
 * claimed. No disk writes, no byte caching, no G2 provider, no route, no
 * transport, no publishing.
 */
object GeneratorBer1Renderer {

    /** Bundled font policy: fontVersion 1 == `res/font/hold_sans.ttf`. */
    val FONT_RESOURCE_ID: Int = dev.hryshyn.remanence.R.font.hold_sans

    /** Note text size in reference-canvas units (fontVersion 1 policy). */
    const val NOTE_FONT_SIZE_CANVAS_UNITS = 16f

    /** Reference canvas in frozen units (ADR-017). */
    const val CANVAS_WIDTH = 360
    const val CANVAS_HEIGHT = 640

    /** Integer rectangle in canvas units. */
    data class RectI(val x: Int, val y: Int, val width: Int, val height: Int)

    /** One photo draw item: placement cell plus its frozen letterbox rect. */
    data class ScenePhoto(val contentId: String, val cell: RectI, val contentRect: RectI)

    /** Immutable draw plan derived only from a validated expression. */
    data class Scene(
        val canvasWidth: Int,
        val canvasHeight: Int,
        val photos: List<ScenePhoto>,
        /** Authored note text; null when absent/empty. */
        val note: String?,
        /** Reserved band (non-null iff the note is non-empty); not a fit claim. */
        val noteRegion: GeneratorExpression.NoteRegion?,
        val fontVersion: Int,
    )

    /** Pure note fit predicate over measured layout metrics (host-testable). */
    object NoteFit {
        data class Metrics(val width: Int, val height: Int, val hasVisualOverflow: Boolean)

        fun fits(metrics: Metrics, region: GeneratorExpression.NoteRegion): Boolean =
            !metrics.hasVisualOverflow &&
                metrics.width in 1..region.width &&
                metrics.height in 1..region.height
    }

    /** Typed renderer admission outcome; never a fabricated success. */
    sealed interface SceneResult {
        data class Ready(val scene: Scene) : SceneResult

        /**
         * Photo scene is valid, but a non-empty note must be measured and
         * drawn by the Compose layer before any success is claimed.
         */
        data class NeedsNoteMeasure(val noteUtf8Bytes: Int, val scene: Scene) : SceneResult

        /** Measured note does not fit the fixed region; no clip/truncation. */
        data class NoteRenderBlocked(val noteUtf8Bytes: Int, val reason: String) : SceneResult

        data class Incompatible(val reason: String) : SceneResult
        data class Rejected(val reasons: List<String>) : SceneResult
    }

    /**
     * True only when every frozen photo has a caller-owned source. The canvas
     * refuses to draw otherwise (no silent omission of authored content).
     */
    fun isDrawable(scene: Scene, sourceIds: Set<String>): Boolean =
        scene.photos.isNotEmpty() && scene.photos.all { it.contentId in sourceIds }

    /**
     * Pure note admissibility facts: the authored note plus the measured
     * metrics of the layout that will be drawn. No Compose types.
     */
    data class NoteRender(
        val note: String,
        val region: GeneratorExpression.NoteRegion,
        val metrics: NoteFit.Metrics,
    )

    /**
     * Fail-closed draw admission for the note layer. A scene with a
     * non-empty note is drawable ONLY with a layout for exactly that note,
     * the fixed frozen region, and metrics that fit; a scene without a note
     * is drawable only with no note layer. Any mismatch draws nothing.
     */
    fun noteDrawable(scene: Scene, render: NoteRender?, hasLayout: Boolean): Boolean {
        val note = scene.note
        if (note == null) return render == null && !hasLayout
        val region = scene.noteRegion ?: return false
        return hasLayout &&
            render != null &&
            render.note == note &&
            render.region == region &&
            NoteFit.fits(render.metrics, region)
    }

    /**
     * Validates [expression] + caller-owned sources into a photo draw plan.
     * Never admits a non-empty note as [SceneResult.Ready].
     */
    fun prepare(
        expression: GeneratorExpression.ResolvedExpression,
        sourceIds: Set<String>,
        sourceDimensions: Map<String, Pair<Int, Int>>,
    ): SceneResult {
        when (val validation = GeneratorExpression.validateResolved(expression)) {
            is GeneratorExpression.InputValidation.Valid -> Unit
            is GeneratorExpression.InputValidation.Invalid ->
                return SceneResult.Rejected(validation.reasons)
        }

        if (expression.expressionContractVersion != GeneratorExpression.EXPRESSION_CONTRACT_V2) {
            return SceneResult.Rejected(
                listOf("unsupported expressionContractVersion ${expression.expressionContractVersion}"),
            )
        }
        if (expression.canvasVersion != GeneratorEditorialRows.CANVAS_VERSION) {
            return SceneResult.Rejected(listOf("unsupported canvasVersion ${expression.canvasVersion}"))
        }
        if (expression.grammarId != GeneratorEditorialRows.GRAMMAR_ID ||
            expression.grammarVersion != GeneratorEditorialRows.GRAMMAR_VERSION ||
            expression.branchId != GeneratorEditorialRows.BRANCH_ID
        ) {
            return SceneResult.Rejected(
                listOf(
                    "unsupported grammar/branch ${expression.grammarId}@" +
                        "${expression.grammarVersion}/${expression.branchId}",
                ),
            )
        }
        if (expression.fontVersion != GeneratorEditorialRows.FONT_VERSION) {
            return SceneResult.Rejected(listOf("unsupported fontVersion ${expression.fontVersion}"))
        }
        if (expression.paletteVersion != GeneratorEditorialRows.PALETTE_VERSION) {
            return SceneResult.Rejected(listOf("unsupported paletteVersion ${expression.paletteVersion}"))
        }

        val authoredIds = expression.input.photos.map { it.contentId }
        val expectedIds = authoredIds.toSet()
        if (sourceIds != expectedIds) {
            return SceneResult.Rejected(
                listOf(
                    "source id mismatch missing=${expectedIds - sourceIds} " +
                        "extra=${sourceIds - expectedIds}",
                ),
            )
        }
        if (sourceDimensions.keys != expectedIds) {
            return SceneResult.Rejected(
                listOf("source dimensions do not match authored content ids"),
            )
        }
        val badDimensions = authoredIds.filter { id ->
            val dims = sourceDimensions[id]
            dims == null || dims.first <= 0 || dims.second <= 0
        }
        if (badDimensions.isNotEmpty()) {
            return SceneResult.Rejected(
                badDimensions.map { "invalid dimensions for $it" },
            )
        }
        // Normalized upright contract: source bitmaps must be EXIF-upright and
        // aspect-equivalent to the authored G1 geometry (resolution may differ).
        val aspectMismatch = expression.input.photos.filter { authored ->
            val dims = sourceDimensions.getValue(authored.contentId)
            dims.first.toLong() * authored.heightPx != dims.second.toLong() * authored.widthPx
        }
        if (aspectMismatch.isNotEmpty()) {
            return SceneResult.Rejected(
                aspectMismatch.map {
                    "bitmap aspect does not match authored geometry for ${it.contentId}"
                },
            )
        }

        val photos = ArrayList<ScenePhoto>(expression.placements.size)
        expression.placements.forEachIndexed { index, placement ->
            val rect = placement.contentRect
                ?: return SceneResult.Rejected(listOf("placement $index is missing contentRect"))
            if (rect.width <= 0 || rect.height <= 0 ||
                rect.x < placement.x || rect.y < placement.y ||
                rect.x.toLong() + rect.width > placement.x.toLong() + placement.width ||
                rect.y.toLong() + rect.height > placement.y.toLong() + placement.height
            ) {
                return SceneResult.Rejected(
                    listOf("placement $index contentRect is not inside its cell"),
                )
            }
            photos += ScenePhoto(
                contentId = placement.contentId,
                cell = RectI(placement.x, placement.y, placement.width, placement.height),
                contentRect = RectI(rect.x, rect.y, rect.width, rect.height),
            )
        }
        if (photos.map { it.contentId } != authoredIds) {
            return SceneResult.Rejected(listOf("placements must match authored content order"))
        }

        val note = expression.input.note?.takeIf { it.isNotEmpty() }
        val scene = Scene(
            canvasWidth = CANVAS_WIDTH,
            canvasHeight = CANVAS_HEIGHT,
            photos = photos,
            note = note,
            noteRegion = if (note == null) null else GeneratorEditorialRows.NOTE_REGION,
            fontVersion = expression.fontVersion,
        )
        return if (note == null) {
            SceneResult.Ready(scene)
        } else {
            SceneResult.NeedsNoteMeasure(note.toByteArray(Charsets.UTF_8).size, scene)
        }
    }
}

/**
 * Draws a validated [GeneratorBer1Renderer.Scene] onto the exact 360x640
 * canvas, fit-whole preserving ratio and centered, from caller-owned in-memory
 * bitmaps; fails closed if any authored source is missing. When [noteLayout]
 * is supplied it is drawn (unmodified) inside the frozen
 * [GeneratorBer1Renderer.Scene.noteRegion] under the same canvas transform.
 */
@Composable
private fun GeneratorBer1Canvas(
    scene: GeneratorBer1Renderer.Scene,
    sources: Map<String, ImageBitmap>,
    modifier: Modifier = Modifier,
    noteRender: GeneratorBer1Renderer.NoteRender? = null,
    noteLayout: TextLayoutResult? = null,
) {
    Canvas(modifier = modifier) {
        if (!GeneratorBer1Renderer.isDrawable(scene, sources.keys)) return@Canvas
        if (!GeneratorBer1Renderer.noteDrawable(scene, noteRender, noteLayout != null)) return@Canvas
        val factor = minOf(size.width / scene.canvasWidth, size.height / scene.canvasHeight)
        if (factor <= 0f) return@Canvas
        val dx = (size.width - scene.canvasWidth * factor) / 2f
        val dy = (size.height - scene.canvasHeight * factor) / 2f
        withTransform({
            translate(dx, dy)
            scale(factor, factor, pivot = Offset.Zero)
        }) {
            scene.photos.forEach { photo ->
                val image = sources.getValue(photo.contentId)
                drawImage(
                    image = image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(image.width, image.height),
                    dstOffset = IntOffset(photo.contentRect.x, photo.contentRect.y),
                    dstSize = IntSize(photo.contentRect.width, photo.contentRect.height),
                    filterQuality = FilterQuality.High,
                )
            }
            val region = scene.noteRegion
            if (noteRender != null && noteLayout != null && region != null) {
                drawText(
                    textLayoutResult = noteLayout,
                    topLeft = Offset(region.x.toFloat(), region.y.toFloat()),
                )
            }
        }
    }
}

/**
 * Isolated preview admission: photos draw on [GeneratorBer1Renderer.SceneResult.Ready];
 * a non-empty note is laid out at canvas scale, admitted only when the same
 * [TextLayoutResult] fully fits [GeneratorEditorialRows.NOTE_REGION], and then
 * drawn with that identical layout. Not wired to any route, screen or transport.
 */
@Composable
fun GeneratorBer1Preview(
    expression: GeneratorExpression.ResolvedExpression,
    sources: Map<String, ImageBitmap>,
    modifier: Modifier = Modifier,
): GeneratorBer1Renderer.SceneResult {
    val dimensions = sources.mapValues { (_, bitmap) -> bitmap.width to bitmap.height }
    val result = remember(expression, sources, dimensions) {
        GeneratorBer1Renderer.prepare(expression, sources.keys, dimensions)
    }
    return when (result) {
        is GeneratorBer1Renderer.SceneResult.Ready -> {
            GeneratorBer1Canvas(result.scene, sources, modifier)
            result
        }

        is GeneratorBer1Renderer.SceneResult.NeedsNoteMeasure -> {
            val scene = result.scene
            val note = scene.note
            val region = scene.noteRegion
            if (note == null || region == null) {
                // Defensive: typed rejection, never a photo-only draw.
                GeneratorBer1Renderer.SceneResult.Rejected(
                    listOf("non-empty note scene is missing text or note region"),
                )
            } else {
                val measurer = rememberCanvasNoteMeasurer()
                val style = rememberCanvasNoteStyle()
                val layout = remember(note, measurer, style, region) {
                    measurer.measure(
                        text = AnnotatedString(note),
                        style = style,
                        overflow = TextOverflow.Clip,
                        softWrap = true,
                        maxLines = Int.MAX_VALUE,
                        constraints = Constraints(maxWidth = region.width, maxHeight = region.height),
                    )
                }
                val metrics = GeneratorBer1Renderer.NoteFit.Metrics(
                    width = layout.size.width,
                    height = layout.size.height,
                    hasVisualOverflow = layout.hasVisualOverflow,
                )
                val render = GeneratorBer1Renderer.NoteRender(note, region, metrics)
                if (GeneratorBer1Renderer.NoteFit.fits(metrics, region)) {
                    GeneratorBer1Canvas(scene, sources, modifier, render, layout)
                    GeneratorBer1Renderer.SceneResult.Ready(scene)
                } else {
                    GeneratorBer1Renderer.SceneResult.NoteRenderBlocked(
                        result.noteUtf8Bytes,
                        "note overflows NOTE_REGION at fontVersion 1",
                    )
                }
            }
        }

        else -> result
    }
}

/**
 * Canvas-scale measurer: density 1 means measured units equal reference-canvas
 * units, so the fit decision is shell-size independent and matches the later
 * scaled draw exactly.
 */
@Composable
internal fun rememberCanvasNoteMeasurer(): TextMeasurer {
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val layoutDirection = LocalLayoutDirection.current
    return remember(fontFamilyResolver, layoutDirection) {
        TextMeasurer(fontFamilyResolver, Density(1f, 1f), layoutDirection, 8)
    }
}

/** Bundled Hold Sans style at the pinned canvas-unit size (fontVersion 1). */
@Composable
internal fun rememberCanvasNoteStyle(): TextStyle {
    val fontFamily = remember { FontFamily(Font(GeneratorBer1Renderer.FONT_RESOURCE_ID, FontWeight.Normal)) }
    return remember(fontFamily) {
        TextStyle(
            fontFamily = fontFamily,
            fontSize = GeneratorBer1Renderer.NOTE_FONT_SIZE_CANVAS_UNITS.sp,
        )
    }
}
