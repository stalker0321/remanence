package dev.hryshyn.remanence.ui.create

import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.CapsulePhotoIdentity
import dev.hryshyn.remanence.core.model.GeneratorBer1Provider
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.CollageCandidate
import java.util.UUID

/**
 * An explicitly frozen collage choice: the exact candidate, its sealed
 * expression, and the recomputed identity/hash values. Immutable; built only
 * by [buildFrozenSelection], never trusted from UI state. This prepares a
 * stable selected expression only — collage publication stays fail-closed
 * (see the guard in `beginGeneratorSession`).
 */
data class CollageFrozenSelection(
    val candidateId: String,
    val expression: GeneratorExpression.ResolvedExpression,
    val projectedHash: String,
    val inputHash: String,
)

/**
 * Pure freeze validator. Returns the frozen selection only when every check
 * passes; `null` (rejected) otherwise, without side effects:
 * - an explicitly selected [candidate] and a current preview [currentInput]
 *   must both be present;
 * - the candidate's expression input must equal the current input (stale
 *   builds rejected);
 * - [GeneratorExpression.validateResolved] must accept the expression;
 * - the candidate identity is recomputed via [GeneratorBer1Provider] and
 *   must equal the carried `candidateId` (tampered identity rejected);
 * - the capsule ID must parse, and the projected hash is recomputed via
 *   [CapsulePhotoIdentity] (never trusted).
 */
internal fun buildFrozenSelection(
    candidate: CollageCandidate?,
    currentInput: GeneratorExpression.GeneratorInput?,
    capsuleId: String,
): CollageFrozenSelection? {
    if (candidate == null || currentInput == null) return null
    val expression = candidate.expression
    if (expression.input != currentInput) return null
    if (GeneratorExpression.validateResolved(expression) !is GeneratorExpression.InputValidation.Valid) {
        return null
    }
    val candidateId = GeneratorBer1Provider.candidateIdFor(expression)
    if (candidateId != candidate.candidateId) return null
    val capsule = runCatching { CapsuleId(UUID.fromString(capsuleId)) }.getOrNull() ?: return null
    return CollageFrozenSelection(
        candidateId = candidateId,
        expression = expression,
        projectedHash = CapsulePhotoIdentity.projectedHash(
            expression = expression,
            candidateId = candidateId,
            capsuleId = capsule,
            contentIds = expression.input.photos.map { it.contentId },
        ),
        inputHash = GeneratorExpression.canonicalHash(expression.input),
    )
}
