package dev.hryshyn.remanence.core.crypto

import dev.hryshyn.remanence.core.model.GeneratorExpression

/**
 * ADR-018 step 3 (receiver): decides whether a decrypted content manifest may
 * be rendered as the exact BER1 layout. v1 has no artifact (caller keeps the
 * existing v1 photo path); v2 is admitted only when the sealed expression is
 * structurally valid and fully consistent with the manifest note and photo
 * ordinals. Any other case returns a typed [Result.Unsupported] so the
 * presentation can render NOTHING instead of a wrong/fallback layout.
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
        return Result.Supported(expression)
    }
}
