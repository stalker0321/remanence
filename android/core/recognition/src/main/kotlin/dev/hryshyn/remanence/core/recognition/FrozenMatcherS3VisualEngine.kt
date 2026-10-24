package dev.hryshyn.remanence.core.recognition

import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprint

/**
 * Visual decision for an S3 query against the locally indexed candidates,
 * returned in the [LocalMatchEngine] result shape so crypto verification and
 * grant issue stay with [LocalMatchEngine.completeVisual].
 *
 * Candidates that are not S3 fingerprints are ignored. The frozen matcher
 * never offers a chooser: it opens one card or asks for a recapture.
 */
class FrozenMatcherS3VisualEngine(private val vocabulary: FrozenMatcherS3.Vocabulary) {

    fun evaluate(query: SiftRootSiftFingerprint, candidates: List<IndexedCandidate>): VisualScanResult {
        val frame = FrozenMatcherS3Fingerprint.queryFrame(query)
            ?: return VisualScanResult.RecaptureRequired(MatchDiagnosticOutcome.RECAPTURE)
        val usable = candidates.filter { FrozenMatcherS3Fingerprint.isS3(it.front) }
        if (usable.isEmpty()) return VisualScanResult.RecaptureRequired(MatchDiagnosticOutcome.RECAPTURE)
        val references = usable.map { candidate ->
            FrozenMatcherS3.reference(candidate.capsuleId.toString(), FrozenMatcherS3Fingerprint.features(candidate.front), vocabulary)
        }
        val decision = FrozenMatcherS3.match(
            FrozenMatcherS3Fingerprint.features(query),
            references,
            vocabulary,
            frame.components,
            requireCentred = true,
        )
        references.forEach { it.release() }
        val id = decision.id ?: return VisualScanResult.RecaptureRequired(MatchDiagnosticOutcome.RECAPTURE)
        val winner = usable.filter { it.capsuleId.toString() == id }
        val origin = if (winner.any { it.recipientPreferred }) CandidateOrigin.RECIPIENT_PREFERRED else CandidateOrigin.SENDER_FALLBACK
        return VisualScanResult.Accepted(winner.first().capsuleId, origin, decision.best?.finalScore ?: 0.0)
    }
}
