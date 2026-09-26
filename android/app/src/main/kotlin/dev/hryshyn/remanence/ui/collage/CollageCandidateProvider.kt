package dev.hryshyn.remanence.ui.collage

import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorBer1Provider
import dev.hryshyn.remanence.core.model.GeneratorExpression
import kotlin.math.ceil

/**
 * Authoritative per-photo grammar-5 crop input (normalized 0..10000). Returns
 * `null` when the on-device analysis that produces the freeze `envelope-50`
 * crops is not available; the provider then fails closed rather than
 * fabricating a center crop.
 */
fun interface CollageCropSource {
    fun cropsFor(photos: List<GeneratorExpression.PhotoRef>): Map<String, GeneratorExpression.CropWindow>?
}

/** One complete, styled, text-only contract-V3 collage candidate. */
data class CollageCandidate(
    val candidateId: String,
    val styleId: String,
    val branchId: String,
    val expression: GeneratorExpression.ResolvedExpression,
)

sealed interface CollageProviderResult {
    data class Candidates(val candidates: List<CollageCandidate>) : CollageProviderResult

    /** Authoritative analysis is missing; nothing is fabricated. */
    data class Unavailable(val reason: String) : CollageProviderResult

    data class Rejected(val reason: String) : CollageProviderResult
}

/**
 * Deterministic sender-side V5 provider for the authoritative grammar-5 /
 * text-only subset: one finished sheet per bundled style (`press`,
 * `blue-letter`, `poster`) built on the already-resolved
 * [GeneratorExpression.GeneratorInput].
 *
 * Pure: it consumes grammar-5 cell geometry, the authoritative crops from
 * [CollageCropSource], the style registry (real STYD01 digest) and the shared
 * text measurer to seal explicit `noteRegion` / text-mode `musicRegion`. No
 * artwork, no mask. Candidate identity is exactly the existing
 * `GeneratorBer1Provider.candidateIdFor` (GENEX03 hash prefix); callers compute
 * the BEXPR02 projection with the existing `CapsulePhotoIdentity` /
 * `GeneratorExpressionProjection` helpers.
 *
 * When [CollageCropSource] returns `null`, the whole result is
 * [CollageProviderResult.Unavailable]: this build has no authoritative
 * grammar-5 analysis, so there is nothing honest to preview.
 */
class CollageCandidateProvider(
    private val cropSource: CollageCropSource,
    private val measurer: CollageRenderer.TextMeasurer,
) {

    fun candidates(
        input: GeneratorExpression.GeneratorInput,
        note: String?,
        track: CapsuleTrackSnapshotV1?,
    ): CollageProviderResult {
        if (input.photos.size !in MIN_PHOTOS..MAX_PHOTOS) {
            return CollageProviderResult.Rejected("select 3..5 photos")
        }
        val crops = cropSource.cropsFor(input.photos)
            ?: return CollageProviderResult.Unavailable(NO_ANALYSIS_REASON)
        val cropByContentId = LinkedHashMap<String, GeneratorExpression.CropWindow>(input.photos.size)
        for (photo in input.photos) {
            val crop = crops[photo.contentId]
                ?: return CollageProviderResult.Rejected("crop analysis is incomplete for ${photo.contentId}")
            if (!validCrop(crop)) {
                return CollageProviderResult.Rejected("crop analysis is invalid for ${photo.contentId}")
            }
            cropByContentId[photo.contentId] = crop
        }

        val noteText = note?.takeIf { it.isNotBlank() }
        val sealedInput = input.copy(note = noteText)
        val built = ArrayList<CollageCandidate>(STYLE_ORDER.size)
        for (styleId in STYLE_ORDER) {
            val style = StyleRegistry.style(styleId)
                ?: return CollageProviderResult.Rejected("style $styleId is not bundled")
            val candidate = buildCandidate(sealedInput, noteText, track, style, cropByContentId)
                ?: return CollageProviderResult.Rejected("the note or music does not fit the grammar-5 grid")
            built += candidate
        }
        return CollageProviderResult.Candidates(built)
    }

    private fun buildCandidate(
        input: GeneratorExpression.GeneratorInput,
        noteText: String?,
        track: CapsuleTrackSnapshotV1?,
        style: StyleRegistry.Style,
        crops: Map<String, GeneratorExpression.CropWindow>,
    ): CollageCandidate? {
        val noteType = style.note.toTextType()
        val musicType = style.music.toTextType()

        val noteHeight: Int? = if (noteText == null) {
            null
        } else {
            val measured = measurer.measure(
                CollageRenderer.TextMeasureRequest(
                    text = noteText,
                    family = noteType.family,
                    size = noteType.size,
                    weight = noteType.weight,
                    italic = noteType.italic,
                    line = noteType.line,
                    maxWidth = CONTENT_WIDTH,
                    maxHeight = MAX_TEXT_BOX,
                ),
            )
            if (!fits(measured)) return null
            measured.height
        }

        val grid = Grammar5Grid.place(
            photoCount = input.photos.size,
            noteHeight = noteHeight,
            music = track != null,
        ) ?: return null

        val musicRegion = grid.musicRegion
        if (track != null) {
            if (musicRegion == null) return null
            val measured = measurer.measure(
                CollageRenderer.TextMeasureRequest(
                    text = track.title + "\n" + track.artistDisplay,
                    family = musicType.family,
                    size = musicType.size,
                    weight = musicType.weight,
                    italic = musicType.italic,
                    line = musicType.line,
                    maxWidth = musicRegion.width,
                    maxHeight = musicRegion.height,
                ),
            )
            if (!fits(measured, maxHeight = musicRegion.height)) return null
        }

        if (input.photos.size != grid.cells.size) return null

        val placements = input.photos.mapIndexed { index, photo ->
            val cell = grid.cells[index]
            GeneratorExpression.Placement(
                contentId = photo.contentId,
                x = cell.x, y = cell.y, width = cell.width, height = cell.height,
                crop = crops.getValue(photo.contentId),
                maskId = null,
                contentRect = null,
            )
        }
        val styleDigest = StyleRegistry.digest(style.id) ?: return null
        val expression = GeneratorExpression.ResolvedExpression(
            canvasVersion = 1,
            grammarId = GRAMMAR_ID,
            grammarVersion = GRAMMAR_VERSION,
            branchId = grid.branchId,
            input = input,
            placements = placements,
            noteTreatment = "regular-region",
            diagnostics = emptyList(),
            fontVersion = FontRegistry.VERSION,
            paletteVersion = CollageRenderer.PALETTE_REGISTRY_VERSION,
            expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V3,
            noteRegion = grid.noteRegion?.let {
                GeneratorExpression.NoteRegion(it.x, it.y, it.width, it.height)
            },
            style = GeneratorExpression.StyleBinding(style.id, style.version, styleDigest),
            noteType = noteType,
            musicRegion = if (musicRegion == null) {
                null
            } else {
                GeneratorExpression.MusicRegion(
                    x = musicRegion.x,
                    y = musicRegion.y,
                    width = musicRegion.width,
                    height = musicRegion.height,
                    mode = "text",
                    type = musicType,
                    artwork = null,
                )
            },
        )
        if (GeneratorExpression.validateResolved(expression) !is GeneratorExpression.InputValidation.Valid) {
            return null
        }
        return CollageCandidate(
            candidateId = GeneratorBer1Provider.candidateIdFor(expression),
            styleId = style.id,
            branchId = grid.branchId,
            expression = expression,
        )
    }

    private fun StyleRegistry.TextStyle.toTextType() = GeneratorExpression.TextType(
        family = font,
        size = size,
        weight = weight,
        italic = italic,
        line = ceil(size * LINE_FACTOR).toInt(),
    )

    private fun validCrop(crop: GeneratorExpression.CropWindow): Boolean =
        crop.x >= 0 && crop.y >= 0 && crop.width > 0 && crop.height > 0 &&
            crop.x.toLong() + crop.width <= CROP_MAX &&
            crop.y.toLong() + crop.height <= CROP_MAX

    private fun fits(
        measurement: CollageRenderer.TextMeasurement,
        maxHeight: Int = MAX_TEXT_BOX,
    ): Boolean =
        !measurement.hasVisualOverflow &&
            measurement.width in 1..CONTENT_WIDTH &&
            measurement.height in 1..maxHeight

    private companion object {
        const val MIN_PHOTOS = 3
        const val MAX_PHOTOS = 5
        const val CROP_MAX = 10_000L
        const val CONTENT_WIDTH = 328
        const val MAX_TEXT_BOX = 300
        const val GRAMMAR_ID = "remanence-editorial-rows"
        const val GRAMMAR_VERSION = 1
        const val LINE_FACTOR = 1.12
        val STYLE_ORDER = listOf("press", "blue-letter", "poster")
        const val NO_ANALYSIS_REASON =
            "authoritative grammar-5 crop analysis is not available on this build"
    }
}
