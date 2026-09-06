package dev.hryshyn.remanence.core.recognition

/**
 * Orders a new proposal source ahead of the legacy detector without changing
 * the shared crop geometry rules. A guide returned for the new source is not
 * attempted first: the legacy detector gets the opportunity to recover.
 */
class LocalizationProposalSelector(
    private val cropSelector: PostcardCropSelector,
) {
    fun orderedAttempts(
        primaryCandidates: List<QuadCandidate>?,
        legacyCandidates: List<QuadCandidate>,
        frameWidth: Int,
        frameHeight: Int,
    ): List<PostcardCropSelection> {
        require(frameWidth > 0 && frameHeight > 0)
        val legacy = cropSelector.select(legacyCandidates, frameWidth, frameHeight)
            .withSource(LocalizationProposalSource.LEGACY_CONTOUR)
        if (primaryCandidates == null) return listOf(legacy)
        val primary = cropSelector.select(primaryCandidates, frameWidth, frameHeight)
            .withSource(LocalizationProposalSource.V2_LINE)
        return if (primary.usedGuideFallback) {
            listOf(legacy)
        } else {
            listOf(primary, legacy)
        }
    }

    private fun PostcardCropSelection.withSource(
        source: LocalizationProposalSource,
    ): PostcardCropSelection = copy(
        proposalSource = if (usedGuideFallback) LocalizationProposalSource.GUIDE_FALLBACK else source,
    )
}
