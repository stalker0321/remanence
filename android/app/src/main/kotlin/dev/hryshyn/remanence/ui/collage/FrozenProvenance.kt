package dev.hryshyn.remanence.ui.collage

import dev.hryshyn.remanence.core.model.GeneratorExpression

/**
 * Frozen V5 provenance seam (T154).
 *
 * Static authored brand/sender boxes transcribed verbatim from the
 * capsule-engine V5 freeze (`runtime/grammars.js` `basePlans` and
 * `runtime/expansion.js` plans). `S` rows carry fixed boxes; every
 * dynamic (`D(...)`) box is computed by the T151 enumerator from its own
 * layout variables and passed through the same [GeneratorExpression]
 * types — this file never invents boxes.
 *
 * Source-of-truth note: `(10,586,340,47)` + vertical sender is grammar
 * **16** (spliced inactive, never produced); `(6,565,350,75)` +
 * `(10,543,340,19)` is grammar **17**; `(10,23,22,555)` +
 * `(44,606,290,20)` is grammar **18**. `interface.md` rows 5–7 carry the
 * corrected owners (F1 fixed 2026-10-02).
 *
 * Identity: brand text is always [GeneratorExpression.BRAND_TEXT];
 * sender text is always `'From ' + verifiedHandle` and a blank handle
 * omits the sender mark (never fabricated).
 */
object BranchProvenance {

    /** Grammar 5 Editorial Rows (static; includes the V4 masthead 64/70/75 yield). */
    private fun editorialRows(senderText: String?): List<GeneratorExpression.ProvenanceMark> {
        val marks = ArrayList<GeneratorExpression.ProvenanceMark>(2)
        marks += GeneratorExpression.ProvenanceMark(
            kind = GeneratorExpression.ProvenanceKind.BRAND,
            text = GeneratorExpression.BRAND_TEXT,
            x = 12, y = 4, width = 340, height = 75,
            family = "StudySerif", size = 64, weight = 400, italic = false,
            line = 70,
            orientation = GeneratorExpression.MarkOrientation.HORIZONTAL,
            align = GeneratorExpression.MarkAlign.LEFT,
        )
        if (senderText != null) {
            marks += GeneratorExpression.ProvenanceMark(
                kind = GeneratorExpression.ProvenanceKind.SENDER,
                text = senderText,
                x = 16, y = 613, width = 328, height = 23,
                family = "StudySerif", size = 20, weight = 400, italic = true,
                line = 21,
                orientation = GeneratorExpression.MarkOrientation.HORIZONTAL,
                align = GeneratorExpression.MarkAlign.RIGHT,
            )
        }
        return marks
    }

    /** Grammar 12 Sequential Strips (static). */
    private fun sequentialStrips(senderText: String?): List<GeneratorExpression.ProvenanceMark> {
        val marks = ArrayList<GeneratorExpression.ProvenanceMark>(2)
        marks += GeneratorExpression.ProvenanceMark(
            kind = GeneratorExpression.ProvenanceKind.BRAND,
            text = GeneratorExpression.BRAND_TEXT,
            x = 2, y = 8, width = 23, height = 570,
            family = "StudyMono", size = 18, weight = 400, italic = false,
            line = 19,
            orientation = GeneratorExpression.MarkOrientation.VERTICAL_DOWN,
            align = GeneratorExpression.MarkAlign.LEFT,
        )
        if (senderText != null) {
            marks += GeneratorExpression.ProvenanceMark(
                kind = GeneratorExpression.ProvenanceKind.SENDER,
                text = senderText,
                x = 28, y = 615, width = 316, height = 20,
                family = "StudyMono", size = 15, weight = 400, italic = false,
                line = 16,
                orientation = GeneratorExpression.MarkOrientation.HORIZONTAL,
                align = GeneratorExpression.MarkAlign.RIGHT,
            )
        }
        return marks
    }

    /** Grammar 17 Continuous Field (static; JS order is sender-first). */
    private fun continuousField(senderText: String?): List<GeneratorExpression.ProvenanceMark> {
        val marks = ArrayList<GeneratorExpression.ProvenanceMark>(2)
        if (senderText != null) {
            marks += GeneratorExpression.ProvenanceMark(
                kind = GeneratorExpression.ProvenanceKind.SENDER,
                text = senderText,
                x = 10, y = 543, width = 340, height = 19,
                family = "StudySans", size = 15, weight = 400, italic = false,
                line = 16,
                orientation = GeneratorExpression.MarkOrientation.HORIZONTAL,
                align = GeneratorExpression.MarkAlign.RIGHT,
            )
        }
        marks += GeneratorExpression.ProvenanceMark(
            kind = GeneratorExpression.ProvenanceKind.BRAND,
            text = GeneratorExpression.BRAND_TEXT,
            x = 6, y = 565, width = 350, height = 75,
            family = "Narrow", size = 72, weight = 700, italic = false,
            line = 74,
            orientation = GeneratorExpression.MarkOrientation.HORIZONTAL,
            align = GeneratorExpression.MarkAlign.LEFT,
        )
        return marks
    }

    /** Grammar 18 Mounted Sheet (static; shared by all mount branches). */
    private fun mountedSheet(senderText: String?): List<GeneratorExpression.ProvenanceMark> {
        val marks = ArrayList<GeneratorExpression.ProvenanceMark>(2)
        marks += GeneratorExpression.ProvenanceMark(
            kind = GeneratorExpression.ProvenanceKind.BRAND,
            text = GeneratorExpression.BRAND_TEXT,
            x = 10, y = 23, width = 22, height = 555,
            family = "StudySans", size = 19, weight = 400, italic = false,
            line = 20,
            orientation = GeneratorExpression.MarkOrientation.VERTICAL_UP,
            align = GeneratorExpression.MarkAlign.LEFT,
        )
        if (senderText != null) {
            marks += GeneratorExpression.ProvenanceMark(
                kind = GeneratorExpression.ProvenanceKind.SENDER,
                text = senderText,
                x = 44, y = 606, width = 290, height = 20,
                family = "StudySans", size = 16, weight = 400, italic = false,
                line = 17,
                orientation = GeneratorExpression.MarkOrientation.HORIZONTAL,
                align = GeneratorExpression.MarkAlign.LEFT,
            )
        }
        return marks
    }

    /** Grammar 21 `letter-full-height-counterpoint` (static; expansion.js:64). */
    private fun letterFullHeight(senderText: String?): List<GeneratorExpression.ProvenanceMark> {
        val marks = ArrayList<GeneratorExpression.ProvenanceMark>(2)
        if (senderText != null) {
            marks += GeneratorExpression.ProvenanceMark(
                kind = GeneratorExpression.ProvenanceKind.SENDER,
                text = senderText,
                x = 10, y = 10, width = 143, height = 29,
                family = "StudySerif", size = 24, weight = 400, italic = true,
                line = 25,
                orientation = GeneratorExpression.MarkOrientation.HORIZONTAL,
                align = GeneratorExpression.MarkAlign.LEFT,
            )
        }
        marks += GeneratorExpression.ProvenanceMark(
            kind = GeneratorExpression.ProvenanceKind.BRAND,
            text = GeneratorExpression.BRAND_TEXT,
            x = 10, y = 550, width = 141, height = 23,
            family = "StudySerif", size = 21, weight = 400, italic = false,
            line = 22,
            orientation = GeneratorExpression.MarkOrientation.HORIZONTAL,
            align = GeneratorExpression.MarkAlign.LEFT,
        )
        return marks
    }

    /**
     * Static marks for (`grammarId`, `branchId`), or null when the branch
     * is dynamic (the T151 enumerator computes it from frozen formulas),
     * inactive (grammars 16/19), or unknown. `senderHandle` is the
     * authenticated verified sender snapshot handle; blank omits the
     * sender mark.
     */
    fun marksFor(
        grammarId: Int,
        branchId: String,
        senderHandle: String? = null,
    ): List<GeneratorExpression.ProvenanceMark>? {
        val senderText = GeneratorExpression.senderMarkText(senderHandle)
        return when (grammarId) {
            5 -> editorialRows(senderText)
            12 -> sequentialStrips(senderText)
            17 -> continuousField(senderText)
            18 -> mountedSheet(senderText)
            21 -> if (branchId == "letter-full-height-counterpoint") {
                letterFullHeight(senderText)
            } else {
                null
            }
            else -> null
        }
    }

    /**
     * Shared mount carrier decor for grammar 18 branches
     * (`expansion` mount set; `grammars.js` base `decor`).
     */
    fun mountCarrierDecor(): List<GeneratorExpression.SceneDecor> = listOf(
        GeneratorExpression.SceneDecor(
            kind = GeneratorExpression.DecorKind.CARRIER,
            x = 6, y = 5, width = 349, height = 627,
        ),
    )
}

/**
 * Fixed-frame crop-correction data seam (T155 contract, T154-owned type).
 *
 * Defined here and NOT wired into [CollageRenderer.prepare] (which keeps
 * rejecting overrides): the future T150 UI consumes it after a solver
 * contract exists. A correction moves the sealed source window by
 * (`dxPx`, `dyPx`) source pixels; [validateCropCorrection] requires the
 * shifted window to stay inside the sealed source bitmap, and a solver
 * re-check is required before any prepare acceptance.
 */
data class CropCorrection(
    val photoContentId: String,
    val dxPx: Int,
    val dyPx: Int,
)

/**
 * Pure bounds check for [CropCorrection]: the sealed crop window shifted
 * by (`dxPx`, `dyPx`) must stay inside the `sourceWidth × sourceHeight`
 * bitmap with positive size. Returns null when valid, else the reason.
 * This is the UI-clamp layer only; solver safety re-check is still
 * required and prepare acceptance is unchanged (corrections rejected).
 */
fun validateCropCorrection(
    sealed: GeneratorExpression.CropWindow,
    sealedSourceWidth: Int,
    sealedSourceHeight: Int,
    correction: CropCorrection,
): String? {
    if (correction.photoContentId.isBlank()) return "crop correction requires a content id"
    if (sealedSourceWidth <= 0 || sealedSourceHeight <= 0) return "invalid source dimensions"
    val left = sealed.x.toLong() + correction.dxPx
    val top = sealed.y.toLong() + correction.dyPx
    if (left < 0 || top < 0) return "crop correction moves the window outside its source"
    if (left + sealed.width > sealedSourceWidth || top + sealed.height > sealedSourceHeight) {
        return "crop correction moves the window outside its source"
    }
    return null
}
