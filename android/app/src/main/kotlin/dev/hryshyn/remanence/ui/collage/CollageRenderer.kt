package dev.hryshyn.remanence.ui.collage

import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorExpression
import java.security.MessageDigest

/**
 * Pure deterministic prepare step for the shared V5 collage renderer.
 *
 * [prepare] consumes an already resolved contract-V3 expression, the manifest
 * note string, the optional sealed track snapshot, decoded source-photo
 * metadata, and an injected [TextMeasurer]. It returns an immutable integer
 * draw [Scene] or a typed [SceneResult.Rejected]; it never draws, never
 * fetches, never re-plans geometry, and never falls back to another renderer.
 *
 * Geometry is fixed to the reference canvas 360 x 640. The host applies its
 * own viewport scale and draws. Colour roles are authoritative: [Scene.paper]
 * is the paper fill, [Scene.ink] is the note (ordinary ink), the music region
 * carries [SceneMusic.background] (`sound`) and [SceneMusic.color]
 * (`soundInk`). A sealed `mode == "thumbnail"` region with verified artwork
 * instead carries [SceneArtwork]; a missing or mismatched cover degrades to the
 * text/no-art layout, never a placeholder. Text is measured only through
 * [TextMeasurer]; a measured block that overflows its sealed rectangle rejects
 * the whole scene, with no implicit shrink, reflow, or line dropping.
 *
 * This file deliberately has no UI-toolkit, image, or planner dependency:
 * only core model types, [StyleRegistry], and [FontRegistry].
 */
object CollageRenderer {

    /** Reference canvas in frozen units. */
    const val CANVAS_WIDTH = 360
    const val CANVAS_HEIGHT = 640

    /** Frozen slice-1 palette registry contract version (STYD01 v1). */
    const val PALETTE_REGISTRY_VERSION = 1

    private const val CROP_MAX = 10_000L

    /** Integer rectangle in canvas units. */
    data class RectI(val x: Int, val y: Int, val width: Int, val height: Int)

    /** Decoded upright source-photo metadata (no pixels held here). */
    data class SourcePhoto(val width: Int, val height: Int)

    /** One photo draw item: sealed placement cell plus its source pixel rect. */
    data class ScenePhoto(val contentId: String, val destination: RectI, val source: RectI)

    /** Note/ordinary-ink text layer. */
    data class SceneText(
        val text: String,
        val region: RectI,
        val family: String,
        val size: Int,
        val weight: Int,
        val italic: Boolean,
        val line: Int,
        val fontResourceId: Int,
        val color: Int,
    )

    /** Music layer: title then artist, on the sound/soundInk roles. */
    data class SceneMusic(
        val title: String,
        val artist: String,
        val text: String,
        val region: RectI,
        val family: String,
        val size: Int,
        val weight: Int,
        val italic: Boolean,
        val line: Int,
        val fontResourceId: Int,
        val background: Int,
        val color: Int,
    )

    /**
     * Verified cover-art layer for a `mode == "thumbnail"` music region. It is
     * emitted only when the caller supplied decoded artwork whose bounds equal
     * the sealed descriptor size; the region is the sealed music rectangle and
     * [background] is the `sound` role. The bitmap itself is owned by the host
     * and never enters the scene.
     */
    data class SceneArtwork(val region: RectI, val background: Int)

    /** Immutable integer draw plan derived only from a validated expression. */
    data class Scene(
        val canvasWidth: Int,
        val canvasHeight: Int,
        val styleId: String,
        val paper: Int,
        val ink: Int,
        val sound: Int,
        val soundInk: Int,
        val photos: List<ScenePhoto>,
        val note: SceneText?,
        val music: SceneMusic?,
        /** Verified thumbnail cover, or null for the text/no-art layout. */
        val artwork: SceneArtwork? = null,
    )

    /** Typed prepare outcome; never a fabricated success. */
    sealed interface SceneResult {
        data class Ready(val scene: Scene) : SceneResult
        data class Rejected(val reason: String) : SceneResult
    }

    /** One text layout request in reference-canvas integer units. */
    data class TextMeasureRequest(
        val text: String,
        val family: String,
        val size: Int,
        val weight: Int,
        val italic: Boolean,
        val line: Int,
        val maxWidth: Int,
        val maxHeight: Int,
    )

    /** Measured layout metrics. Units are reference-canvas pixels. */
    data class TextMeasurement(
        val width: Int,
        val height: Int,
        val hasVisualOverflow: Boolean,
    )

    /**
     * Pure injected text measurement. The host must measure exactly the
     * request it later draws; [prepare] treats a non-fitting measurement as a
     * whole-scene rejection.
     */
    fun interface TextMeasurer {
        fun measure(request: TextMeasureRequest): TextMeasurement
    }

    private fun fits(measurement: TextMeasurement, region: RectI): Boolean =
        !measurement.hasVisualOverflow &&
            measurement.width in 1..region.width &&
            measurement.height in 1..region.height

    /** Convert a `#rrggbb` palette string to an opaque `0xFFRRGGBB` int. */
    private fun parseColor(hex: String): Int {
        require(hex.length == 7 && hex[0] == '#') { "palette colour must be #rrggbb, got $hex" }
        return 0xFF000000.toInt() or hex.substring(1).toInt(16)
    }

    /**
     * Normalized crop 0..10000 to a source pixel rectangle, with right/bottom
     * edge closure so `crop.x + crop.width == 10000` lands exactly on the
     * bitmap edge. Returns `null` for non-positive sources or a crop that
     * collapses below one pixel.
     */
    fun sourceRect(crop: GeneratorExpression.CropWindow, sourceWidth: Int, sourceHeight: Int): RectI? {
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val left = crop.x.toLong() * sourceWidth / CROP_MAX
        val top = crop.y.toLong() * sourceHeight / CROP_MAX
        val right = (crop.x + crop.width).toLong() * sourceWidth / CROP_MAX
        val bottom = (crop.y + crop.height).toLong() * sourceHeight / CROP_MAX
        if (left < 0 || top < 0 || right > sourceWidth || bottom > sourceHeight) return null
        val width = (right - left).toInt()
        val height = (bottom - top).toInt()
        if (width < 1 || height < 1) return null
        return RectI(left.toInt(), top.toInt(), width, height)
    }

    private fun inCanvas(rect: RectI): Boolean =
        rect.x >= 0 && rect.y >= 0 && rect.width > 0 && rect.height > 0 &&
            rect.x.toLong() + rect.width <= CANVAS_WIDTH &&
            rect.y.toLong() + rect.height <= CANVAS_HEIGHT

    /**
     * Validates and lays out one sealed collage. Rejection is ordered and
     * first-failure-wins; see the class KDoc for the contract.
     */
    fun prepare(
        expression: GeneratorExpression.ResolvedExpression,
        note: String?,
        trackSnapshot: CapsuleTrackSnapshotV1?,
        sources: Map<String, SourcePhoto>,
        measurer: TextMeasurer,
        /**
         * Decoded bounds of the fetched-and-verified cover, or null. Required
         * only for a sealed `mode == "thumbnail"` music region; when it is
         * absent or does not match the sealed descriptor size the renderer
         * degrades to the text/no-art layout and never draws a placeholder.
         */
        artwork: SourcePhoto? = null,
    ): SceneResult {
        if (expression.expressionContractVersion != GeneratorExpression.EXPRESSION_CONTRACT_V3) {
            return SceneResult.Rejected(
                "collage renderer requires a contract V3 expression, got " +
                    "${expression.expressionContractVersion}",
            )
        }
        if (GeneratorExpression.validateResolved(expression) !is GeneratorExpression.InputValidation.Valid) {
            return SceneResult.Rejected("expression artifact failed validation")
        }

        // Authenticity: the manifest note argument must be the sealed note,
        // normalized identically (blank => absent). Rejected before any scene
        // construction so a mismatched note can never paint, and note presence
        // must agree with noteRegion presence in both directions.
        val noteText = note?.takeIf { it.isNotBlank() }
        val sealedNote = expression.input.note?.takeIf { it.isNotBlank() }
        if (noteText != sealedNote) {
            return SceneResult.Rejected("manifest note does not match the sealed expression note")
        }
        if ((noteText != null) != (expression.noteRegion != null)) {
            return SceneResult.Rejected(
                if (noteText != null) {
                    "non-empty note requires a sealed note region"
                } else {
                    "sealed note region requires a non-empty note"
                },
            )
        }

        val styleBinding = expression.style
            ?: return SceneResult.Rejected("v3 expression is missing its style binding")
        val style = StyleRegistry.style(styleBinding.styleId)
            ?: return SceneResult.Rejected("style ${styleBinding.styleId} is not a bundled collage style")
        if (styleBinding.styleVersion != style.version) {
            return SceneResult.Rejected("style version does not match the bundled style record")
        }
        val expectedDigest = requireNotNull(StyleRegistry.digest(style.id))
        if (!MessageDigest.isEqual(styleBinding.digest, expectedDigest)) {
            return SceneResult.Rejected("style digest does not match the bundled style record")
        }
        if (expression.fontVersion != FontRegistry.VERSION) {
            return SceneResult.Rejected("unsupported fontVersion ${expression.fontVersion}")
        }
        if (expression.paletteVersion != PALETTE_REGISTRY_VERSION) {
            return SceneResult.Rejected("unsupported paletteVersion ${expression.paletteVersion}")
        }

        val noteType = expression.noteType
            ?: return SceneResult.Rejected("v3 expression is missing its note type")
        val noteFont = FontRegistry.resourceFor(noteType.family, noteType.weight, noteType.italic)
            ?: return SceneResult.Rejected(
                "font face ${noteType.family}/${noteType.weight}/${noteType.italic} is not bundled",
            )

        val musicRegion = expression.musicRegion
        val musicType = musicRegion?.type
        val musicFont = if (musicRegion != null && musicType != null) {
            FontRegistry.resourceFor(musicType.family, musicType.weight, musicType.italic)
                ?: return SceneResult.Rejected(
                    "font face ${musicType.family}/${musicType.weight}/${musicType.italic} is not bundled",
                )
        } else {
            null
        }

        if ((musicRegion != null) != (trackSnapshot != null)) {
            return SceneResult.Rejected("music region and track snapshot must match")
        }

        val placements = expression.placements
        placements.forEachIndexed { index, placement ->
            if (placement.maskId != null) {
                return SceneResult.Rejected("placement $index carries a mask")
            }
            if (placement.contentRect != null) {
                return SceneResult.Rejected("placement $index carries a contentRect")
            }
            if (placement.crop == null) {
                return SceneResult.Rejected("placement $index is missing its crop")
            }
            if (!inCanvas(RectI(placement.x, placement.y, placement.width, placement.height))) {
                return SceneResult.Rejected("placement $index is outside the 360x640 canvas")
            }
        }
        expression.noteRegion?.let {
            if (!inCanvas(RectI(it.x, it.y, it.width, it.height))) {
                return SceneResult.Rejected("note region is outside the 360x640 canvas")
            }
        }
        musicRegion?.let {
            if (!inCanvas(RectI(it.x, it.y, it.width, it.height))) {
                return SceneResult.Rejected("music region is outside the 360x640 canvas")
            }
        }

        val authoredIds = expression.input.photos.map { it.contentId }
        if (sources.keys != authoredIds.toSet()) {
            return SceneResult.Rejected("source metadata does not match authored content")
        }

        val photos = ArrayList<ScenePhoto>(authoredIds.size)
        placements.forEachIndexed { index, placement ->
            val source = sources.getValue(placement.contentId)
            if (source.width <= 0 || source.height <= 0) {
                return SceneResult.Rejected("invalid source dimensions for ${placement.contentId}")
            }
            val rect = sourceRect(requireNotNull(placement.crop), source.width, source.height)
                ?: return SceneResult.Rejected("placement $index crop is outside its source bitmap")
            photos += ScenePhoto(
                contentId = placement.contentId,
                destination = RectI(placement.x, placement.y, placement.width, placement.height),
                source = rect,
            )
        }
        if (photos.map { it.contentId } != authoredIds) {
            return SceneResult.Rejected("placements must match authored content order")
        }

        val noteScene = if (noteText == null) {
            null
        } else {
            val region = requireNotNull(expression.noteRegion)
            val regionRect = RectI(region.x, region.y, region.width, region.height)
            val measurement = measurer.measure(
                TextMeasureRequest(
                    text = noteText,
                    family = noteType.family,
                    size = noteType.size,
                    weight = noteType.weight,
                    italic = noteType.italic,
                    line = noteType.line,
                    maxWidth = region.width,
                    maxHeight = region.height,
                ),
            )
            if (!fits(measurement, regionRect)) {
                return SceneResult.Rejected("note does not fit its sealed region")
            }
            SceneText(
                text = noteText,
                region = regionRect,
                family = noteType.family,
                size = noteType.size,
                weight = noteType.weight,
                italic = noteType.italic,
                line = noteType.line,
                fontResourceId = noteFont,
                color = parseColor(style.palette.ink),
            )
        }

        val musicScene: SceneMusic?
        val artworkScene: SceneArtwork?
        if (musicRegion == null || musicType == null) {
            musicScene = null
            artworkScene = null
        } else {
            val snapshot = requireNotNull(trackSnapshot)
            val regionRect = RectI(musicRegion.x, musicRegion.y, musicRegion.width, musicRegion.height)
            val descriptor = musicRegion.artwork
            val verified = descriptor != null &&
                artwork != null &&
                artwork.width == descriptor.width &&
                artwork.height == descriptor.height
            if (verified) {
                // A1 guarantees `mode == "thumbnail"` whenever artwork is sealed.
                musicScene = null
                artworkScene = SceneArtwork(regionRect, parseColor(style.palette.sound))
            } else {
                // `mode == "text"`, or a thumbnail whose cover could not be
                // fetched/verified: render the sealed words (the no-art
                // layout). Never a placeholder cover.
                val text = snapshot.title + "\n" + snapshot.artistDisplay
                val measurement = measurer.measure(
                    TextMeasureRequest(
                        text = text,
                        family = musicType.family,
                        size = musicType.size,
                        weight = musicType.weight,
                        italic = musicType.italic,
                        line = musicType.line,
                        maxWidth = musicRegion.width,
                        maxHeight = musicRegion.height,
                    ),
                )
                if (!fits(measurement, regionRect)) {
                    return SceneResult.Rejected("music does not fit its sealed region")
                }
                musicScene = SceneMusic(
                    title = snapshot.title,
                    artist = snapshot.artistDisplay,
                    text = text,
                    region = regionRect,
                    family = musicType.family,
                    size = musicType.size,
                    weight = musicType.weight,
                    italic = musicType.italic,
                    line = musicType.line,
                    fontResourceId = requireNotNull(musicFont),
                    background = parseColor(style.palette.sound),
                    color = parseColor(style.palette.soundInk),
                )
                artworkScene = null
            }
        }

        return SceneResult.Ready(
            Scene(
                canvasWidth = CANVAS_WIDTH,
                canvasHeight = CANVAS_HEIGHT,
                styleId = style.id,
                paper = parseColor(style.palette.paper),
                ink = parseColor(style.palette.ink),
                sound = parseColor(style.palette.sound),
                soundInk = parseColor(style.palette.soundInk),
                photos = photos,
                note = noteScene,
                music = musicScene,
                artwork = artworkScene,
            ),
        )
    }
}
