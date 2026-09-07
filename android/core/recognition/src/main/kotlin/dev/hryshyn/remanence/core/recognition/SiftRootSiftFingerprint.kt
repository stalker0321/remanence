package dev.hryshyn.remanence.core.recognition

/** One bounded SIFT keypoint record; all wire metadata is integer-valued. */
data class SiftRootSiftKeypoint(
    val xMicro: Int,
    val yMicro: Int,
    val scaleMicro: Int,
    val angleCentiDegrees: Int,
    val responseQuantized: Int,
    val octave: Int,
)

/**
 * P0 storage value for the `postcard-sift-rootsift-v1` profile.
 *
 * Descriptor rows contain raw quantized 128-byte SIFT values. RootSIFT is
 * derived only during P2 matching; this class deliberately has no extraction
 * or matching behavior and must not double-transform the stored rows. An
 * all-zero row is storage-valid, but [descriptorRowIsMatcherUsable] reports it
 * unusable to the future matcher. Constructor-supplied descriptor arrays
 * remain caller-owned; [wipe] is available when that caller explicitly
 * transfers disposal responsibility. Arrays returned by the codec parser are
 * fresh codec/domain-owned rows and are wipeable through [wipe].
 */
class SiftRootSiftFingerprint(
    val profileId: String,
    val canonicalWidthPx: Int,
    val canonicalHeightPx: Int,
    val coarseHash64: Long,
    val keypoints: List<SiftRootSiftKeypoint>,
    val quantizedSiftDescriptors: List<ByteArray>,
) {
    fun descriptorRowIsMatcherUsable(index: Int): Boolean {
        require(index in quantizedSiftDescriptors.indices) { "descriptor row index out of range" }
        return quantizedSiftDescriptors[index].any { it.toInt() != 0 }
    }

    /** Clears descriptor bytes owned by this decoded value. */
    fun wipe() {
        quantizedSiftDescriptors.forEach { it.fill(0) }
    }
}
