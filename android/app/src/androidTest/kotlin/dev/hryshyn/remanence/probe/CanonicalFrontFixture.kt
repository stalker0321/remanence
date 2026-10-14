package dev.hryshyn.remanence.probe

import dev.hryshyn.remanence.core.recognition.RecognitionProfile

/**
 * T186 androidTest-only canonical FRONT builders (frozen codec bytes,
 * caller owns everything returned — parse or wipe in `finally`).
 *
 * Both share the existing spread grid (8x8 keypoints, 64 descriptors);
 * only the descriptor-row construction differs:
 * - [saturatingFront] keeps the historical seed-11 `coerceIn(1, 255)`
 *   defect: every byte saturates to 255, so the production ratio gate
 *   rejects all rows. Regression negative only — never published.
 * - [distinctFront] uses distinct modulo-256 rows: a genuine match
 *   candidate whose outcome the production matcher decides at runtime
 *   (NOT pre-proven here).
 */
internal object CanonicalFrontFixture {

    fun saturatingFront(seed: Int = 11): ByteArray =
        buildFront(seed) { byteIndex, keypointIndex ->
            ((byteIndex * 7 + keypointIndex * 13 + seed * 29).coerceIn(1, 255)).toByte()
        }

    fun distinctFront(seed: Int = 11): ByteArray =
        buildFront(seed) { byteIndex, keypointIndex ->
            ((byteIndex * 7 + keypointIndex * 13 + seed * 29) % 256).toByte()
        }

    private fun buildFront(
        seed: Int,
        descriptor: (byteIndex: Int, keypointIndex: Int) -> Byte,
    ): ByteArray {
        val profile = RecognitionProfile.postcardSiftRootSiftV1()
        val micro = dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec.MICRO_UNITS
        val keypoints = List(64) {
            dev.hryshyn.remanence.core.model.SiftRootSiftKeypoint(
                xMicro = Math.rint((it % 8) / 8.0 * micro).toInt(),
                yMicro = Math.rint((it / 8) / 8.0 * micro).toInt(),
                scaleMicro = micro,
                angleCentiDegrees = 0,
                responseQuantized = it,
                octave = 0,
            )
        }
        val fp = dev.hryshyn.remanence.core.model.SiftRootSiftFingerprint(
            profileId = profile.profileId,
            canonicalWidthPx = profile.capture.canonicalLongEdgePx,
            canonicalHeightPx = 1000,
            coarseHash64 = seed.toLong(),
            keypoints = keypoints,
            quantizedSiftDescriptors = List(64) { i ->
                ByteArray(dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec.DESCRIPTOR_BYTES) {
                    descriptor(it, i)
                }
            },
        )
        return try {
            dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec.serialize(fp)
        } finally {
            fp.wipe()
        }
    }
}
