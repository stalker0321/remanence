package dev.hryshyn.remanence.core.model

/**
 * BER1 bounded editorial-rows layout planner (ADR-017), model-only.
 *
 * Scope (strict): pure deterministic geometry over an already-validated G1
 * input — row patterns 2+1 / 2+2 / 2+2+1 for 3/4/5 authored photos, 360x640
 * canvas, margin 16, gap 12, exact integer tiling, and an aspect-preserving
 * letterbox `contentRect` inside every photo cell. The result is a validated
 * G1 v2 [GeneratorExpression.ResolvedExpression] (or an explicit typed
 * non-success). There is deliberately NO G2 provider, NO candidate id, NO
 * renderer, NO UI, NO publishing, and NO wire change here.
 *
 * Note split (no fake fit): a NON-EMPTY note is never assumed to fit from its
 * character/byte count. Without an injected [NoteMeasurementPort] the planner
 * returns [PlanResult.NeedsNoteMeasure] so a real versioned renderer can
 * measure full text; with a port it uses only the port's explicit verdict.
 * Absent (`null`) or empty (`""`) notes carry no note region and can be laid
 * out immediately.
 *
 * Gate: every letterboxed photo whose rendered short side is below
 * [MIN_SHORT_SIDE] (48 reference units) yields [PlanResult.Incompatible]
 * (arch §4/§6: applicable but unresolvable stops, never silently adapts).
 * The 48-unit value is provisional (arch §7) and calibration-required.
 *
 * Provisional ids: no production grammar/branch id exists in the tree, so the
 * constants below are ADR-017 placeholders. Promoting/rendering/delivering
 * these expressions requires `docs/hold/generator-boundary.md` gates 1–4 and
 * is out of scope.
 */
object GeneratorEditorialRows {

    /** Provisional ADR-017 grammar identity. */
    const val GRAMMAR_ID = "ber1-editorial-rows"
    const val GRAMMAR_VERSION = 1
    const val BRANCH_ID = "ber1-rows-3-5"
    const val CANVAS_VERSION = 1

    /** Provisional dependency policy versions (strictly positive). */
    const val FONT_VERSION = 1
    const val PALETTE_VERSION = 1

    /** Fixed frame in reference units (ADR-017). */
    const val MARGIN = 16
    const val GAP = 12

    /** Minimum rendered short side per photo (reference units, provisional). */
    const val MIN_SHORT_SIDE = 48

    /**
     * Fixed bottom band a measured renderer may use (ADR-017). Never attached
     * by the planner without an explicit [NoteMeasurement.Fits] verdict.
     */
    val NOTE_REGION: GeneratorExpression.NoteRegion =
        GeneratorExpression.NoteRegion(16, 512, 328, 128)

    /** Typed planner outcome. Never a G2 candidate and never a silent success. */
    sealed interface PlanResult {
        data class Planned(val expression: GeneratorExpression.ResolvedExpression) : PlanResult

        /** Non-empty note present; text fit must be measured by a real renderer. */
        data class NeedsNoteMeasure(val noteUtf8Bytes: Int) : PlanResult

        /** Structurally applicable but unresolvable (e.g. aspect gate). */
        data class Incompatible(val reason: String) : PlanResult

        /** G1-invalid input; fail closed without producing an expression. */
        data class Invalid(val reasons: List<String>) : PlanResult
    }

    /** Explicit measured-note verdict; the fit claim belongs to the renderer. */
    sealed interface NoteMeasurement {
        data class Fits(val region: GeneratorExpression.NoteRegion) : NoteMeasurement
        data class DoesNotFit(val reason: String) : NoteMeasurement
    }

    /** Versioned renderer seam for full-text note measurement. */
    fun interface NoteMeasurementPort {
        fun measure(note: String): NoteMeasurement
    }

    /** One laid-out photo cell in canvas units. */
    data class Cell(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * Plans BER1 for [input]. Deterministic: same input (and same explicit
     * measurement) yields byte-identical placements and canonical hash.
     */
    fun plan(
        input: GeneratorExpression.GeneratorInput,
        noteMeasurement: NoteMeasurementPort? = null,
    ): PlanResult {
        when (val validation = GeneratorExpression.validate(input)) {
            is GeneratorExpression.InputValidation.Valid -> Unit
            is GeneratorExpression.InputValidation.Invalid ->
                return PlanResult.Invalid(validation.reasons)
        }

        val note = input.note
        val noteRegion: GeneratorExpression.NoteRegion?
        if (note == null || note.isEmpty()) {
            noteRegion = null
        } else {
            if (noteMeasurement == null) {
                return PlanResult.NeedsNoteMeasure(note.toByteArray(Charsets.UTF_8).size)
            }
            noteRegion = when (val measured = noteMeasurement.measure(note)) {
                is NoteMeasurement.DoesNotFit ->
                    return PlanResult.Incompatible("note: ${measured.reason}")
                is NoteMeasurement.Fits ->
                    if (measured.region != NOTE_REGION) {
                        return PlanResult.Incompatible(
                            "note measurement must use the fixed ADR-017 region",
                        )
                    } else {
                        measured.region
                    }
            }
        }

        val photoTop = MARGIN
        val photoBottom = noteRegion?.y?.minus(GAP)
            ?: (GeneratorExpression.CANVAS_HEIGHT_UNITS - MARGIN)
        if (photoBottom - photoTop <= 0) {
            return PlanResult.Incompatible("note region leaves no photo area")
        }

        val cells = cellsFor(input.photos.size, photoTop, photoBottom)
        val contentRects = input.photos.mapIndexed { index, photo -> fit(photo, cells[index]) }
        contentRects.forEachIndexed { index, rect ->
            val shortSide = minOf(rect.width, rect.height)
            if (shortSide < MIN_SHORT_SIDE) {
                return PlanResult.Incompatible(
                    "placement $index short side $shortSide < $MIN_SHORT_SIDE",
                )
            }
        }

        val expression = build(input, noteRegion, cells, contentRects)
        return when (val resolved = GeneratorExpression.validateResolved(expression)) {
            is GeneratorExpression.InputValidation.Valid -> PlanResult.Planned(expression)
            is GeneratorExpression.InputValidation.Invalid ->
                PlanResult.Invalid(resolved.reasons)
        }
    }

    /** ADR-017 row patterns for 3/4/5 authored photos. */
    fun rowPattern(photoCount: Int): List<Int> = when (photoCount) {
        3 -> listOf(2, 1)
        4 -> listOf(2, 2)
        5 -> listOf(2, 2, 1)
        else -> throw IllegalArgumentException("BER1 supports 3..5 photos, got $photoCount")
    }

    /** Even row-height split with the remainder (+1 unit) to the earliest rows. */
    fun rowHeights(photoCount: Int, photoTop: Int, photoBottom: Int): List<Int> {
        val rows = rowPattern(photoCount).size
        val available = (photoBottom - photoTop) - (rows - 1) * GAP
        val base = available / rows
        val remainder = available % rows
        return List(rows) { index -> base + if (index < remainder) 1 else 0 }
    }

    /** Authored-order cells tiling the photo area exactly. */
    fun cellsFor(photoCount: Int, photoTop: Int, photoBottom: Int): List<Cell> {
        val pattern = rowPattern(photoCount)
        val heights = rowHeights(photoCount, photoTop, photoBottom)
        val contentWidth = GeneratorExpression.CANVAS_WIDTH_UNITS - 2 * MARGIN
        val cells = ArrayList<Cell>(photoCount)
        var y = photoTop
        pattern.forEachIndexed { rowIndex, count ->
            val height = heights[rowIndex]
            if (count == 1) {
                cells += Cell(MARGIN, y, contentWidth, height)
            } else {
                val cellWidth = (contentWidth - GAP) / 2
                cells += Cell(MARGIN, y, cellWidth, height)
                cells += Cell(MARGIN + cellWidth + GAP, y, cellWidth, height)
            }
            y += height + GAP
        }
        return cells
    }

    /**
     * Letterboxes the whole original into [cell] preserving aspect ratio
     * (`scale = min(cellW/w, cellH/h)`, floored), centered, using Long
     * arithmetic so extreme declared dimensions cannot overflow.
     */
    fun fit(photo: GeneratorExpression.PhotoRef, cell: Cell): GeneratorExpression.ContentRect {
        val sourceWidth = photo.widthPx
        val sourceHeight = photo.heightPx
        val widthLimited = cell.width.toLong() * sourceHeight <= cell.height.toLong() * sourceWidth
        val renderedWidth: Int
        val renderedHeight: Int
        if (widthLimited) {
            renderedWidth = cell.width
            renderedHeight = (sourceHeight.toLong() * cell.width / sourceWidth).toInt()
        } else {
            renderedHeight = cell.height
            renderedWidth = (sourceWidth.toLong() * cell.height / sourceHeight).toInt()
        }
        val offsetX = (cell.width - renderedWidth) / 2
        val offsetY = (cell.height - renderedHeight) / 2
        return GeneratorExpression.ContentRect(
            x = cell.x + offsetX,
            y = cell.y + offsetY,
            width = renderedWidth,
            height = renderedHeight,
        )
    }

    private fun build(
        input: GeneratorExpression.GeneratorInput,
        noteRegion: GeneratorExpression.NoteRegion?,
        cells: List<Cell>,
        contentRects: List<GeneratorExpression.ContentRect>,
    ): GeneratorExpression.ResolvedExpression {
        val noteTreatment = when {
            noteRegion != null -> "note:measured"
            input.note == null -> "note:absent"
            else -> "note:empty"
        }
        val placements = input.photos.mapIndexed { index, photo ->
            val cell = cells[index]
            GeneratorExpression.Placement(
                contentId = photo.contentId,
                x = cell.x,
                y = cell.y,
                width = cell.width,
                height = cell.height,
                crop = null,
                maskId = null,
                contentRect = contentRects[index],
            )
        }
        return GeneratorExpression.ResolvedExpression(
            canvasVersion = CANVAS_VERSION,
            grammarId = GRAMMAR_ID,
            grammarVersion = GRAMMAR_VERSION,
            branchId = BRANCH_ID,
            input = input,
            placements = placements,
            noteTreatment = noteTreatment,
            diagnostics = listOf(
                "ber1:rows=${rowPattern(input.photos.size).joinToString("+")}",
                "ber1:minShortSide=$MIN_SHORT_SIDE",
            ),
            fontVersion = FONT_VERSION,
            paletteVersion = PALETTE_VERSION,
            expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V2,
            noteRegion = noteRegion,
        )
    }
}
