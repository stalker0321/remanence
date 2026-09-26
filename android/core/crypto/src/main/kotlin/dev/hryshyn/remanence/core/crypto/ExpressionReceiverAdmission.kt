package dev.hryshyn.remanence.core.crypto

import dev.hryshyn.remanence.core.model.GeneratorEditorialRows
import dev.hryshyn.remanence.core.model.GeneratorExpression

/**
 * ADR-018 step 3 (receiver): decides whether a decrypted content manifest may
 * be rendered as the exact BER1 layout. v1 has no artifact (caller keeps the
 * existing v1 photo path); v2 is admitted only when the sealed expression is
 * structurally valid and fully consistent with the manifest note and photo
 * ordinals. Any other case returns a typed [Result.Unsupported] so the
 * presentation can render NOTHING instead of a wrong/fallback layout.
 *
 * V5 finished collage (contract V3, artifact expression_version 2) additionally
 * gates on receiver readiness below (R6/R7/R8/AD1/AD2); structural rules
 * A1-A9 stay in [GeneratorExpression.validateResolved] and are deliberately
 * not duplicated here. Legacy/V1/V2 paths are unchanged.
 */
object ExpressionReceiverAdmission {

    sealed interface Result {
        data class Supported(
            val expression: GeneratorExpression.ResolvedExpression,
        ) : Result

        data class Unsupported(val reason: String) : Result
    }

    fun admit(content: ContentManifestContent): Result {
        if (content.protocolVersion != 2) {
            return Result.Unsupported("content manifest carries no v2 expression artifact")
        }
        val expression = content.expression?.expression
            ?: return Result.Unsupported("v2 expression artifact is missing")
        if (GeneratorExpression.validateResolved(expression) !is GeneratorExpression.InputValidation.Valid) {
            return Result.Unsupported("expression artifact failed validation")
        }
        val note = content.note?.takeIf { it.isNotEmpty() }
        if ((note == null) != (expression.noteRegion == null)) {
            return Result.Unsupported("expression note region does not match the manifest note")
        }
        if (expression.placements.map { it.contentId } != expression.input.photos.map { it.contentId }) {
            return Result.Unsupported("expression placements are not in source order")
        }
        val photos = content.photos.sortedBy { it.ordinal }
        if (expression.input.photos.size != photos.size) {
            return Result.Unsupported("expression source count does not match the manifest photos")
        }
        if (expression.input.photos.map { it.ordinal } != photos.map { it.ordinal }) {
            return Result.Unsupported("expression source ordinals do not match the manifest photos")
        }
        if (expression.expressionContractVersion == GeneratorExpression.EXPRESSION_CONTRACT_V3) {
            // R6: receiver can only render typefaces in the bundled font
            // matrix (prior contract section 8: Narrow is 700-only, StudyMono
            // is 400 non-italic, italic is StudySerif @400 only). Structural
            // shape already passed :29 above; this is render readiness.
            // Style-record digest comparison against the bundled registry
            // belongs to the StyleDigest slice, not to this gate.
            val types = listOfNotNull(expression.noteType, expression.musicRegion?.type)
            if (types.any { !isBundledFace(it) }) {
                return Result.Unsupported("expression style is not supported")
            }
            // R7 slice-1 capability (mirror of AD1): the encoder never writes
            // mask_id yet and slice 1 renders rect/absent only.
            if (expression.placements.any { it.maskId != null }) {
                return Result.Unsupported("expression mask is not supported")
            }
            // R8: registry contract versions; both stay 1 in slice 1 and must
            // never be re-pointed at different bytes (prior contract Rule 1).
            if (expression.fontVersion != GeneratorEditorialRows.FONT_VERSION ||
                expression.paletteVersion != GeneratorEditorialRows.PALETTE_VERSION
            ) {
                return Result.Unsupported("expression font or palette version is not supported")
            }
            // AD1 slice-1 capability: no fetcher and no cover renderer exist
            // yet, and placeholders are forbidden — artwork-bearing V3 fails
            // closed here until the fetch/verify/render path lands (at which
            // point AD1 is removed, not widened). A thumbnail WITHOUT artwork
            // never reaches this line: :29 rejects it first.
            if (expression.musicRegion?.mode == "thumbnail") {
                return Result.Unsupported("music artwork rendering is not supported")
            }
            // AD2 defense-in-depth for codec R4b (sealed input already died
            // there); same predicate, Unsupported instead of throw.
            if ((expression.musicRegion != null) != (content.trackSnapshot != null)) {
                return Result.Unsupported("expression music region and track snapshot must match")
            }
        }
        return Result.Supported(expression)
    }

    /**
     * Prior-contract section 8 face-availability matrix over the bundled
     * slice-1 fonts: every family the registry can render, with the exact
     * weight/italic cells the freeze ships.
     */
    private fun isBundledFace(type: GeneratorExpression.TextType): Boolean {
        if (type.family !in GeneratorExpression.TEXT_FAMILIES) return false
        return when (type.family) {
            "Narrow" -> type.weight == 700 && !type.italic
            "StudyMono" -> type.weight == 400 && !type.italic
            "StudySerif" -> (type.weight == 400 || type.weight == 700) && (!type.italic || type.weight == 400)
            else -> (type.weight == 400 || type.weight == 700) && !type.italic
        }
    }
}
