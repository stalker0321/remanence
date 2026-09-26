package dev.hryshyn.remanence.ui.collage

import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorBer1Provider
import dev.hryshyn.remanence.core.model.GeneratorExpression
import kotlin.math.ceil

/**
 * One placement's crop request: the sealed content id plus the exact target
 * slot geometry the crop must be computed for. Crops are per (photo, slot), so
 * a single global per-photo map must never be reused across differing slots.
 */
data class CollageCropRequest(
    val contentId: String,
    val slotWidth: Int,
    val slotHeight: Int,
    val shape: String = "rect",
    val zooms: List<Double> = DEFAULT_SLOT_ZOOMS,
) {
    companion object {
        /** Freeze grammar-5 `bands(..., zooms:[1.05,1])`. */
        val DEFAULT_SLOT_ZOOMS: List<Double> = listOf(1.05, 1.0)
    }
}

/**
 * Authoritative crop port. Returns the normalized 0..10000
 * [GeneratorExpression.CropWindow] for every request, or `null` when the
 * on-device analysis that produces the freeze `envelope-50` crops is not
 * available; the provider then fails closed rather than fabricating a center
 * crop.
 */
fun interface CollageCropSource {
    fun cropsFor(requests: List<CollageCropRequest>): Map<String, GeneratorExpression.CropWindow>?
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
 * [CollageCropSource] (requested per placement slot, because each style's note
 * type changes the grid), the style registry (real STYD01 digest) and the
 * shared text measurer to seal explicit `noteRegion` / text-mode
 * `musicRegion`. No artwork, no mask. Candidate identity is exactly the
 * existing `GeneratorBer1Provider.candidateIdFor` (GENEX03 hash prefix);
 * callers compute the BEXPR02 projection with the existing
 * `CapsulePhotoIdentity` / `GeneratorExpressionProjection` helpers.
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
        val noteText = note?.takeIf { it.isNotBlank() }
        val sealedInput = input.copy(note = noteText)
        val built = ArrayList<CollageCandidate>(STYLE_ORDER.size)
        for (styleId in STYLE_ORDER) {
            val style = StyleRegistry.style(styleId)
                ?: return CollageProviderResult.Rejected("style $styleId is not bundled")
            when (val result = buildCandidate(sealedInput, noteText, track, style)) {
                is BuildResult.Built -> built += result.candidate
                BuildResult.Unavailable -> return CollageProviderResult.Unavailable(NO_ANALYSIS_REASON)
                BuildResult.Invalid ->
                    return CollageProviderResult.Rejected("the note, music, or crop does not fit the grammar-5 grid")
            }
        }
        return CollageProviderResult.Candidates(built)
    }

    private sealed interface BuildResult {
        data class Built(val candidate: CollageCandidate) : BuildResult
        data object Unavailable : BuildResult
        data object Invalid : BuildResult
    }

    private fun buildCandidate(
        input: GeneratorExpression.GeneratorInput,
        noteText: String?,
        track: CapsuleTrackSnapshotV1?,
        style: StyleRegistry.Style,
    ): BuildResult {
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
            if (!fits(measured)) return BuildResult.Invalid
            measured.height
        }

        val grid = Grammar5Grid.place(
            photoCount = input.photos.size,
            noteHeight = noteHeight,
            music = track != null,
        ) ?: return BuildResult.Invalid

        val musicRegion = grid.musicRegion
        if (track != null) {
            if (musicRegion == null) return BuildResult.Invalid
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
            if (!fits(measured, maxHeight = musicRegion.height)) return BuildResult.Invalid
        }

        if (input.photos.size != grid.cells.size) return BuildResult.Invalid

        // Aspect-aware crop request: one request per placement, using THIS
        // style's exact cell geometry. A global per-photo crop map must not be
        // reused when candidate slots differ.
        val requests = input.photos.mapIndexed { index, photo ->
            CollageCropRequest(
                contentId = photo.contentId,
                slotWidth = grid.cells[index].width,
                slotHeight = grid.cells[index].height,
            )
        }
        val crops = cropSource.cropsFor(requests) ?: return BuildResult.Unavailable
        val cropByContentId = LinkedHashMap<String, GeneratorExpression.CropWindow>(requests.size)
        for (request in requests) {
            val crop = crops[request.contentId] ?: return BuildResult.Invalid
            if (!validCrop(crop)) return BuildResult.Invalid
            cropByContentId[request.contentId] = crop
        }

        val placements = input.photos.mapIndexed { index, photo ->
            val cell = grid.cells[index]
            GeneratorExpression.Placement(
                contentId = photo.contentId,
                x = cell.x, y = cell.y, width = cell.width, height = cell.height,
                crop = cropByContentId.getValue(photo.contentId),
                maskId = null,
                contentRect = null,
            )
        }
        val styleDigest = StyleRegistry.digest(style.id) ?: return BuildResult.Invalid
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
            return BuildResult.Invalid
        }
        return BuildResult.Built(
            CollageCandidate(
                candidateId = GeneratorBer1Provider.candidateIdFor(expression),
                styleId = style.id,
                branchId = grid.branchId,
                expression = expression,
            ),
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
