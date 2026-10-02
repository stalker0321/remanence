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
 * crop. Suspend: analysis may run detectors; callers provide the structured
 * cancellation scope (the Compose host uses its `LaunchedEffect`).
 */
fun interface CollageCropSource {
    suspend fun cropsFor(requests: List<CollageCropRequest>): Map<String, GeneratorExpression.CropWindow>?
}

/** One requested slot the crop source could not solve, with its frozen cause. */
data class CollageSlotRejection(
    val contentId: String,
    val slotWidth: Int,
    val slotHeight: Int,
    val shape: String,
    val reason: CropRejectReason,
)

/**
 * Optional diagnostic capability a [CollageCropSource] may also implement. It
 * never participates in the fail-closed crop contract: a source that does not
 * implement it, or reports nothing for a slot, leaves the provider on its typed
 * `NO_CROP_CANDIDATE` fallback. The returned crops, omission and substitution
 * behavior are unchanged.
 */
interface CollageCropDiagnostics {
    /** Rejections recorded by the most recent [CollageCropSource.cropsFor] call. */
    val lastCropRejections: List<CollageSlotRejection>
}

/**
 * Typed, stable diagnostic for the dominant frozen solver rejection on one
 * (style, photo, slot). [safeSummary] is bounded debug evidence; it carries no
 * image bytes and never replaces the user-facing rejection copy.
 */
data class CollageRejection(
    val styleId: String,
    val contentId: String,
    val slotIndex: Int,
    val slotWidth: Int,
    val slotHeight: Int,
    val cause: CropRejectReason,
) {
    fun safeSummary(): String =
        "style=$styleId slot=$slotIndex photo=$contentId " +
            "size=${slotWidth}x${slotHeight} cause=${cause.name} " +
            "frozen=\"${cause.frozenReason}\""
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

    /**
     * Fail-closed rejection. [reason] is the unchanged user-facing copy;
     * [rejection] is the optional typed cause. It is present for a solver crop
     * omission (the source's cause, or the `NO_CROP_CANDIDATE` fallback) and
     * null for the non-crop gates (note/music/grid/validation).
     */
    data class Rejected(
        val reason: String,
        val rejection: CollageRejection? = null,
    ) : CollageProviderResult
}

/**
 * Deterministic sender-side V5 provider for the representable grammar-5
 * ("Editorial Rows") subset: every frozen `recipes(N) × power × weightSet`
 * branch for each bundled style (`press`, `blue-letter`, `poster`) built on the
 * already-resolved [GeneratorExpression.GeneratorInput].
 *
 * Frozen parity (T151): the branch enumeration is ported from the V5 freeze
 * `grammars.js` (`Grammar5Branches`). Each style × branch is attempted
 * independently, exactly as the frozen `resolve()` loop tries the next branch
 * when one fails. A branch whose crop is rejected, whose note/music text cannot
 * fit its sealed region, or that fails expression validation is **omitted with
 * its typed cause**; the healthy branches from the same or another style are
 * still returned. The whole result is `Rejected` only when every style × branch
 * fails, and `Unavailable` only when the crop source reports no authoritative
 * analysis.
 *
 * Pure: it consumes grammar-5 cell geometry, the authoritative crops from
 * [CollageCropSource] (requested per placement slot), the style registry (real
 * STYD01 digest) and the shared text measurer to seal explicit `noteRegion` and
 * a `musicRegion` whose `mode`/`artwork` follow rule A1. The frozen provenance/
 * brand/sender marks, decor and non-rect shapes are not representable in the
 * current sealed expression (see `tasks/T151/parity.md` §5) and are absent here;
 * they are a named seam, not silently claimed as ported.
 *
 * Candidate identity is exactly the existing
 * `GeneratorBer1Provider.candidateIdFor` (GENEX03 hash prefix); callers compute
 * the BEXPR02 projection with the existing `CapsulePhotoIdentity` /
 * `GeneratorExpressionProjection` helpers.
 */
class CollageCandidateProvider(
    private val cropSource: CollageCropSource,
    private val measurer: CollageRenderer.TextMeasurer,
) {

    suspend fun candidates(
        input: GeneratorExpression.GeneratorInput,
        note: String?,
        track: CapsuleTrackSnapshotV1?,
        artwork: GeneratorExpression.ArtworkRef? = null,
    ): CollageProviderResult {
        if (input.photos.size !in MIN_PHOTOS..MAX_PHOTOS) {
            return CollageProviderResult.Rejected("select 3..5 photos")
        }
        val noteText = note?.takeIf { it.isNotBlank() }
        val sealedInput = input.copy(note = noteText)
        // Source width/height drive the frozen `power=1` proportional widths.
        val aspects = input.photos.map { photo ->
            if (photo.heightPx > 0) photo.widthPx.toDouble() / photo.heightPx.toDouble() else 1.0
        }
        val built = ArrayList<CollageCandidate>()
        var firstRejection: CollageRejection? = null
        for (styleId in STYLE_ORDER) {
            val style = StyleRegistry.style(styleId)
                ?: return CollageProviderResult.Rejected("style $styleId is not bundled")
            when (val outcome = buildStyle(sealedInput, noteText, track, style, artwork, aspects)) {
                StyleOutcome.Unavailable -> return CollageProviderResult.Unavailable(NO_ANALYSIS_REASON)
                is StyleOutcome.Built -> built += outcome.candidates
                is StyleOutcome.Failed -> if (firstRejection == null) firstRejection = outcome.rejection
            }
        }
        // Every style × branch was omitted/rejected: fail closed with the first
        // typed crop cause when one exists, else the unchanged generic copy.
        if (built.isEmpty()) {
            return CollageProviderResult.Rejected(REJECT_COPY, firstRejection)
        }
        return CollageProviderResult.Candidates(built)
    }

    private sealed interface StyleOutcome {
        data class Built(val candidates: List<CollageCandidate>) : StyleOutcome
        data object Unavailable : StyleOutcome

        /** No branch of this style could seal; [rejection] is the first typed cause. */
        data class Failed(val rejection: CollageRejection?) : StyleOutcome
    }

    private sealed interface BranchOutcome {
        data class Built(val candidate: CollageCandidate) : BranchOutcome
        data class Rejected(val rejection: CollageRejection?) : BranchOutcome

        /** This branch's note/music text cannot fit its sealed region: skip it. */
        data object Skipped : BranchOutcome
        data object Unavailable : BranchOutcome
    }

    private suspend fun buildStyle(
        input: GeneratorExpression.GeneratorInput,
        noteText: String?,
        track: CapsuleTrackSnapshotV1?,
        style: StyleRegistry.Style,
        artwork: GeneratorExpression.ArtworkRef?,
        aspects: List<Double>,
    ): StyleOutcome {
        val noteType = style.note.toTextType()
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
            if (!fits(measured)) return StyleOutcome.Failed(null)
            measured.height
        }
        val grids = Grammar5Branches.grids(aspects, noteHeight, track != null)
        if (grids.isEmpty()) return StyleOutcome.Failed(null)
        val styleCandidates = ArrayList<CollageCandidate>(grids.size)
        var styleRejection: CollageRejection? = null
        // Frozen selection policy is "geometrically distinct layouts": when two
        // branch variants share the exact cell layout (e.g. `power` does not
        // change widths because every source has the same aspect ratio) only the
        // first is offered, so navigation never lands on a duplicate rendering.
        val seenGeometry = HashSet<List<CollageRenderer.RectI>>()
        for (grid in grids) {
            if (!seenGeometry.add(grid.cells)) continue
            when (val branch = buildBranch(input, noteText, track, style, artwork, noteType, grid)) {
                BranchOutcome.Unavailable -> return StyleOutcome.Unavailable
                is BranchOutcome.Built -> styleCandidates += branch.candidate
                is BranchOutcome.Rejected -> if (styleRejection == null) styleRejection = branch.rejection
                BranchOutcome.Skipped -> Unit
            }
        }
        return if (styleCandidates.isNotEmpty()) {
            StyleOutcome.Built(styleCandidates)
        } else {
            StyleOutcome.Failed(styleRejection)
        }
    }

    private suspend fun buildBranch(
        input: GeneratorExpression.GeneratorInput,
        noteText: String?,
        track: CapsuleTrackSnapshotV1?,
        style: StyleRegistry.Style,
        artwork: GeneratorExpression.ArtworkRef?,
        noteType: GeneratorExpression.TextType,
        grid: Grammar5Branches.Grid,
    ): BranchOutcome {
        val musicType = style.music.toTextType()
        val musicRegion = grid.musicRegion
        if (track != null) {
            if (musicRegion == null) return BranchOutcome.Rejected(null)
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
            if (!fits(measured, maxHeight = musicRegion.height)) return BranchOutcome.Skipped
        }
        if (input.photos.size != grid.cells.size) return BranchOutcome.Rejected(null)

        // Aspect-aware crop request: one request per placement, using THIS
        // branch's exact cell geometry, so a global per-photo crop map is never
        // reused across differing branches.
        val requests = input.photos.mapIndexed { index, photo ->
            CollageCropRequest(
                contentId = photo.contentId,
                slotWidth = grid.cells[index].width,
                slotHeight = grid.cells[index].height,
            )
        }
        val crops = cropSource.cropsFor(requests) ?: return BranchOutcome.Unavailable
        val diagnostics = cropSource as? CollageCropDiagnostics
        val cropByContentId = LinkedHashMap<String, GeneratorExpression.CropWindow>(requests.size)
        for ((index, request) in requests.withIndex()) {
            val crop = crops[request.contentId]
            if (crop == null) {
                val cause = diagnostics?.lastCropRejections
                    ?.firstOrNull {
                        it.contentId == request.contentId &&
                            it.slotWidth == request.slotWidth &&
                            it.slotHeight == request.slotHeight &&
                            it.shape == request.shape
                    }
                    ?.reason
                    ?: CropRejectReason.NO_CROP_CANDIDATE
                return BranchOutcome.Rejected(
                    CollageRejection(
                        styleId = style.id,
                        contentId = request.contentId,
                        slotIndex = index,
                        slotWidth = request.slotWidth,
                        slotHeight = request.slotHeight,
                        cause = cause,
                    ),
                )
            }
            if (!validCrop(crop)) return BranchOutcome.Rejected(null)
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
        val styleDigest = StyleRegistry.digest(style.id) ?: return BranchOutcome.Rejected(null)
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
                    // A1 biconditional: thumbnail exactly when a verified
                    // descriptor is present; otherwise the words-only music
                    // line. Absence of artwork never blocks the candidate.
                    mode = if (artwork != null) "thumbnail" else "text",
                    type = musicType,
                    artwork = artwork,
                )
            },
        )
        if (GeneratorExpression.validateResolved(expression) !is GeneratorExpression.InputValidation.Valid) {
            return BranchOutcome.Rejected(null)
        }
        return BranchOutcome.Built(
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
        const val REJECT_COPY = "the note, music, or crop does not fit the grammar-5 grid"
        const val NO_ANALYSIS_REASON =
            "authoritative grammar-5 crop analysis is not available on this build"
    }
}
