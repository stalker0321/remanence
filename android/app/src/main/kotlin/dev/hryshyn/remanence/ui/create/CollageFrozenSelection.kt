package dev.hryshyn.remanence.ui.create

import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.CapsulePhotoIdentity
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorBer1Provider
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.CollageCandidate
import java.util.UUID

/**
 * An explicitly frozen collage choice: the exact candidate, its sealed
 * expression, and the recomputed identity/hash values. Immutable; built only
 * by [buildFrozenSelection], never trusted from UI state. This prepares a
 * stable selected expression; [acceptFrozenForPublish] independently
 * revalidates it at the publication boundary.
 */
data class CollageFrozenSelection(
    val candidateId: String,
    val expression: GeneratorExpression.ResolvedExpression,
    val projectedHash: String,
    val inputHash: String,
)

/** Pure, side-effect-free acceptance result for the sender publish boundary. */
internal sealed interface FrozenPublishAcceptance {
    data class Accepted(val selection: CollageFrozenSelection) : FrozenPublishAcceptance
    data class Refused(val reason: String) : FrozenPublishAcceptance
}

/**
 * Revalidates an explicitly frozen V5 expression against the originals read
 * for this publication and the track snapshot captured before suspension.
 * Nothing is repaired or regenerated: any inconsistency is a typed refusal.
 */
internal fun acceptFrozenForPublish(
    frozen: CollageFrozenSelection?,
    prereadInput: GeneratorExpression.GeneratorInput,
    trackSnapshot: CapsuleTrackSnapshotV1?,
    capsuleId: String,
): FrozenPublishAcceptance {
    frozen ?: return FrozenPublishAcceptance.Refused("no collage has been frozen")
    val expression = frozen.expression
    if (expression.expressionContractVersion != GeneratorExpression.EXPRESSION_CONTRACT_V3) {
        return FrozenPublishAcceptance.Refused("the frozen collage is not a V5 expression")
    }
    if (expression.input != prereadInput) {
        return FrozenPublishAcceptance.Refused("the frozen collage is stale for this content")
    }
    if (GeneratorExpression.validateResolved(expression) !is GeneratorExpression.InputValidation.Valid) {
        return FrozenPublishAcceptance.Refused("the frozen collage is invalid")
    }
    val candidateId = GeneratorBer1Provider.candidateIdFor(expression)
    if (candidateId != frozen.candidateId) {
        return FrozenPublishAcceptance.Refused("the frozen collage identity changed")
    }
    val capsule = runCatching { CapsuleId(UUID.fromString(capsuleId)) }.getOrNull()
        ?: return FrozenPublishAcceptance.Refused("the capsule identity is invalid")
    val projectedHash = CapsulePhotoIdentity.projectedHash(
        expression = expression,
        candidateId = candidateId,
        capsuleId = capsule,
        contentIds = expression.input.photos.map { it.contentId },
    )
    if (projectedHash != frozen.projectedHash) {
        return FrozenPublishAcceptance.Refused("the frozen collage projection changed")
    }
    if (GeneratorExpression.canonicalHash(expression.input) != frozen.inputHash) {
        return FrozenPublishAcceptance.Refused("the frozen collage input changed")
    }

    val musicRegionPresent = expression.musicRegion != null
    if (musicRegionPresent != (trackSnapshot != null)) {
        return FrozenPublishAcceptance.Refused("the frozen collage music does not match the track snapshot")
    }
    val music = expression.input.music
    if (musicRegionPresent != (music != null)) {
        return FrozenPublishAcceptance.Refused("the frozen collage music input is inconsistent")
    }
    if (music != null) {
        if (music.artworkId != null) {
            return FrozenPublishAcceptance.Refused("music artwork is not supported")
        }
        if (music.trackId != trackSnapshot?.trackId?.toString()) {
            return FrozenPublishAcceptance.Refused("the frozen collage names a different track")
        }
    }
    return FrozenPublishAcceptance.Accepted(frozen)
}

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
