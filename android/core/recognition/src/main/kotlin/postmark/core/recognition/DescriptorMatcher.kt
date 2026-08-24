package postmark.core.recognition

/**
 * One ratio-tested, mutually consistent descriptor match. Indices address the
 * keypoint/descriptor lists of the query and reference fingerprints.
 */
data class RatioMutualMatch(
    val queryIndex: Int,
    val referenceIndex: Int,
    val hammingDistance: Int,
)

/**
 * M1-M01 step 1 of docs/recognition.md section 7: brute-force ORB matching
 * with Hamming distance, KNN k=2, the ratio test
 * `best / second <= ratioThreshold`, and reverse matching that keeps only
 * mutually consistent pairs. Both fingerprints must carry the same profile id;
 * anything else is rejected before any matching work.
 */
class DescriptorMatcher(
    private val ratioThreshold: Double = RATIO_TEST_THRESHOLD,
) {

    fun match(query: PostcardFingerprint, reference: PostcardFingerprint): List<RatioMutualMatch> {
        if (query.profileId != reference.profileId) {
            throw IllegalArgumentException("fingerprint profile mismatch")
        }
        if (query.descriptors.isEmpty() || reference.descriptors.isEmpty()) {
            return emptyList()
        }

        // Forward KNN(k=2) plus ratio test.
        val forward = HashMap<Int, ForwardMatch>(query.descriptors.size)
        for (q in query.descriptors.indices) {
            var bestIndex = -1
            var bestDistance = Int.MAX_VALUE
            var secondDistance = Int.MAX_VALUE
            for (r in reference.descriptors.indices) {
                val distance = hamming(query.descriptors[q], reference.descriptors[r])
                when {
                    distance < bestDistance -> {
                        secondDistance = bestDistance
                        bestDistance = distance
                        bestIndex = r
                    }
                    distance < secondDistance -> secondDistance = distance
                }
            }
            if (bestIndex >= 0 && secondDistance != Int.MAX_VALUE &&
                bestDistance.toDouble() / secondDistance.toDouble() <= ratioThreshold
            ) {
                forward[q] = ForwardMatch(bestIndex, bestDistance)
            }
        }

        // Reverse KNN(k=2) plus ratio test over the reference set.
        val reverse = HashMap<Int, Int>(reference.descriptors.size)
        for (r in reference.descriptors.indices) {
            var bestIndex = -1
            var bestDistance = Int.MAX_VALUE
            var secondDistance = Int.MAX_VALUE
            for (q in query.descriptors.indices) {
                val distance = hamming(reference.descriptors[r], query.descriptors[q])
                when {
                    distance < bestDistance -> {
                        secondDistance = bestDistance
                        bestDistance = distance
                        bestIndex = q
                    }
                    distance < secondDistance -> secondDistance = distance
                }
            }
            if (bestIndex >= 0 && secondDistance != Int.MAX_VALUE &&
                bestDistance.toDouble() / secondDistance.toDouble() <= ratioThreshold
            ) {
                reverse[r] = bestIndex
            }
        }

        // Keep only mutually consistent pairs, ordered by query index.
        return forward.entries
            .filter { (queryIndex, forwardMatch) -> reverse[forwardMatch.referenceIndex] == queryIndex }
            .map { (queryIndex, forwardMatch) ->
                RatioMutualMatch(queryIndex, forwardMatch.referenceIndex, forwardMatch.distance)
            }
            .sortedBy { it.queryIndex }
    }

    private data class ForwardMatch(val referenceIndex: Int, val distance: Int)

    private companion object {
        /** docs/recognition.md section 7 step 3. */
        const val RATIO_TEST_THRESHOLD: Double = 0.75

        fun hamming(left: ByteArray, right: ByteArray): Int {
            require(left.size == right.size) { "descriptor length mismatch" }
            var distance = 0
            for (i in left.indices) {
                val xor = (left[i].toInt() xor right[i].toInt()) and 0xFF
                distance += Integer.bitCount(xor)
            }
            return distance
        }
    }
}
