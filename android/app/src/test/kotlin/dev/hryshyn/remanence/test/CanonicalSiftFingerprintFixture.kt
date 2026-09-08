package dev.hryshyn.remanence.test

import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprint
import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec
import dev.hryshyn.remanence.core.model.SiftRootSiftKeypoint

/** Canonical FRONT-only SIFT bytes for app tests that do not exercise OpenCV. */
object CanonicalSiftFingerprintFixture {

    fun bytes(
        seed: Int = 0,
        keypointCount: Int = 64,
        width: Int = 1600,
        height: Int = 1000,
    ): ByteArray = SiftRootSiftFingerprintCodec.serialize(
        fingerprint(seed, keypointCount, width, height),
    )

    fun fingerprint(
        seed: Int = 0,
        keypointCount: Int = 64,
        width: Int = 1600,
        height: Int = 1000,
    ): SiftRootSiftFingerprint {
        require(keypointCount > 0)
        return SiftRootSiftFingerprint(
            profileId = SiftRootSiftFingerprintCodec.PROFILE_ID,
            canonicalWidthPx = width,
            canonicalHeightPx = height,
            coarseHash64 = seed.toLong(),
            keypoints = List(keypointCount) { index ->
                SiftRootSiftKeypoint(
                    xMicro = Math.rint((index % 8) / 8.0 * SiftRootSiftFingerprintCodec.MICRO_UNITS).toInt(),
                    yMicro = Math.rint((index / 8) / 8.0 * SiftRootSiftFingerprintCodec.MICRO_UNITS).toInt(),
                    scaleMicro = SiftRootSiftFingerprintCodec.MICRO_UNITS,
                    angleCentiDegrees = 0,
                    responseQuantized = index,
                    octave = 0,
                )
            },
            quantizedSiftDescriptors = List(keypointCount) { index ->
                ByteArray(SiftRootSiftFingerprintCodec.DESCRIPTOR_BYTES) { byteIndex ->
                    ((byteIndex * 7 + index * 13 + seed * 29) and 0xFF).toByte()
                }
            },
        )
    }
}

/** Deterministic scalar SIFT evidence for app tests whose subject is routing or crypto. */
object DeterministicSiftMatcher {
    fun port() = dev.hryshyn.remanence.core.recognition.SiftRootSiftMatcherPort {
        query, reference ->
        val queryUsable = query.quantizedSiftDescriptors.count { row -> row.any { it.toInt() != 0 } }
        val referenceUsable = reference.quantizedSiftDescriptors.count { row -> row.any { it.toInt() != 0 } }
        val count = if (query.coarseHash64 == reference.coarseHash64) {
            minOf(queryUsable, referenceUsable)
        } else {
            0
        }
        val pairs = (0 until count).map { index ->
            dev.hryshyn.remanence.core.recognition.SiftRootSiftMatchPair(index, index, 0.1)
        }
        dev.hryshyn.remanence.core.recognition.SiftRootSiftMatchResult(
            matches = pairs,
            inlierMatchIndices = pairs.indices.toList(),
            homographyRowMajor = if (count >= 6) {
                doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
            } else {
                null
            },
            diagnostics = dev.hryshyn.remanence.core.recognition.SiftRootSiftMatchDiagnostics(
                rawQueryRows = query.quantizedSiftDescriptors.size,
                rawReferenceRows = reference.quantizedSiftDescriptors.size,
                usableQueryRows = queryUsable,
                usableReferenceRows = referenceUsable,
                forwardRatioMatches = count,
                reverseRatioMatches = count,
                reciprocalMatches = count,
                uniqueMatches = count,
                geometryAttempted = count >= 6,
                geometryFound = count >= 6,
                geometryAccepted = count >= 6,
                inliers = count,
                inlierRatio = if (count == 0) 0.0 else 1.0,
                medianInlierReprojectionErrorPx = if (count == 0) -1.0 else 1.0,
                referenceConvexHullCoverage = if (count == 0) -1.0 else 1.0,
                supportAreaPx2 = if (count == 0) 0.0 else 400.0,
                supportEdgeRatio = if (count == 0) 0.0 else 1.0,
                failure = when {
                    count >= 6 -> dev.hryshyn.remanence.core.recognition.SiftRootSiftMatchFailure.NONE
                    count == 0 -> dev.hryshyn.remanence.core.recognition.SiftRootSiftMatchFailure.NO_RATIO_MATCHES
                    else -> dev.hryshyn.remanence.core.recognition.SiftRootSiftMatchFailure.INSUFFICIENT_UNIQUE_PAIRS
                },
            ),
        )
    }
}
