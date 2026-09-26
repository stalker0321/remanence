package dev.hryshyn.remanence.core.crypto

import dev.hryshyn.remanence.core.model.GeneratorExpression

/**
 * ADR-018 step 3 (receiver): decides whether a decrypted content manifest may
 * be rendered as the exact BER1 layout. v1 has no artifact (caller keeps the
 * existing v1 photo path); v2 is admitted only when the sealed expression is
 * structurally valid and fully consistent with the manifest note and photo
 * ordinals. Any other case returns a typed [Result.Unsupported] so the
 * presentation can render NOTHING instead of a wrong/fallback layout.
 *
 * Structural rules A1-A9 live in [GeneratorExpression.validateResolved] and
 * are deliberately not duplicated here. The note <-> noteRegion equivalence
 * rule is a legacy v2 (BER1) rule and is scoped to
 * [GeneratorExpression.EXPRESSION_CONTRACT_V2]; v1 has no noteRegion slot.
 *
 * V5 finished collage (contract V3, artifact expression_version 2) fails
 * closed unconditionally until the shared collage renderer and the receiver
 * registries land: no V3 expression can be rendered today, so admitting one
 * would report readiness that does not exist.
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
        if (expression.expressionContractVersion == GeneratorExpression.EXPRESSION_CONTRACT_V3) {
            // Temporary fail-closed until the shared collage renderer and the
            // receiver registries land. Detail gates (artwork, style record,
            // font/palette, mask) are intentionally not restated: no V3
            // expression can reach a renderer today, so there is no readiness
            // policy to pretend to.
            return Result.Unsupported("finished collage rendering is not supported")
        }
        if (expression.expressionContractVersion == GeneratorExpression.EXPRESSION_CONTRACT_V2) {
            val note = content.note?.takeIf { it.isNotEmpty() }
            if ((note == null) != (expression.noteRegion == null)) {
                return Result.Unsupported("expression note region does not match the manifest note")
            }
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
        return Result.Supported(expression)
    }
}
