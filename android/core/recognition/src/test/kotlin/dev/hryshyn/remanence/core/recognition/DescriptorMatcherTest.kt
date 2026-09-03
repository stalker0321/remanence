package dev.hryshyn.remanence.core.recognition

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Fixed-descriptor proof for the Hamming KNN-ratio + reverse-mutual matcher.
 * All descriptors differ only in their first bytes so Hamming distances are
 * hand-verifiable; every asserted distance is re-checked in-test.
 */
class DescriptorMatcherTest {

    private fun keypoint(i: Int) = FingerprintKeypoint(
        xNormalized = 0.1 * (i + 1),
        yNormalized = 0.2 * (i + 1),
        scaleNormalized = 1.0,
        angleCentiDegrees = 0,
        responseQuantized = i,
        octave = 0,
    )

    private fun fingerprint(
        descriptors: List<ByteArray>,
        profileId: String = "mvp-orb-v1",
    ) = PostcardFingerprint(
        profileId = profileId,
        canonicalWidthPx = 1600,
        canonicalHeightPx = 1000,
        coarseHash64 = UUID.nameUUIDFromBytes(descriptors.firstOrNull() ?: ByteArray(1)).mostSignificantBits,
        keypoints = descriptors.indices.map { keypoint(it) },
        descriptors = descriptors,
        quality = ExtractionQuality(100.0, 80.0, 0.01, 0.8),
    )

    private fun descriptor(firstByte: Int): ByteArray =
        ByteArray(32).also { it[0] = firstByte.toByte() }

    private fun popcount(left: ByteArray, right: ByteArray): Int =
        left.indices.sumOf { Integer.bitCount((left[it].toInt() xor right[it].toInt()) and 0xFF) }

    @Test
    fun identicalSetsMatchThemselvesFullyWithZeroDistance() {
        val descriptors = (0 until 12).map { descriptor(it * 17) }
        val query = fingerprint(descriptors)
        val reference = fingerprint(descriptors)

        val matches = DescriptorMatcher().match(query, reference)

        assertEquals(descriptors.indices.toList(), matches.map { it.queryIndex })
        assertEquals(descriptors.indices.toList(), matches.map { it.referenceIndex })
        assertTrue(matches.all { it.hammingDistance == 0 })
    }

    @Test
    fun ratioBoundaryExactlyAtThresholdIsAccepted() {
        // Probe 0x3F: dist to X=0x00 is 6, to Y=0xC0 is 8 -> 6/8 == 0.75 accepted.
        val x = descriptor(0x00)
        val y = descriptor(0xC0)
        val probe = descriptor(0x3F)
        // Filler lives in byte 1 so it can never enter the probe's KNN race,
        // but it gives the reverse pass a real second-best query.
        val filler = ByteArray(32).also { it[1] = 0xFF.toByte() }
        assertEquals(6, popcount(probe, x))
        assertEquals(8, popcount(probe, y))
        assertEquals(14, popcount(probe, filler)) // disjoint bytes add: 6 + 8

        val matches = DescriptorMatcher().match(
            fingerprint(listOf(probe, filler)),
            fingerprint(listOf(x, y)),
        )

        // The boundary pair survives as mutual; the filler also passes forward
        // toward X but loses the reverse vote to the probe, so it must stay absent.
        assertEquals(listOf(0 to 0), matches.map { it.queryIndex to it.referenceIndex })
        assertEquals(6, matches.single().hammingDistance)
    }

    @Test
    fun ambiguousRatioAboveThresholdIsDroppedAndOnlyThen() {
        // Query QW=0x3F,00: dist to RW=0x00,00 is 6, to RX=0x40,00 is 7 -> 6/7 = 0.857 rejected.
        // Without the ratio test QW->RW would still win forward, and RW's own
        // nearest query is QW (6 vs 14), i.e. the pair is otherwise mutual.
        val rw = descriptor(0x00)
        val rx = ByteArray(32).also {
            it[0] = 0x40.toByte()
            it[1] = 0x00
        }
        val qw = ByteArray(32).also {
            it[0] = 0x3F
            it[1] = 0x00
        }
        val qo = ByteArray(32).also {
            it[0] = 0xFF.toByte()
            it[1] = 0x3F
        }
        assertEquals(6, popcount(qw, rw))
        assertEquals(7, popcount(qw, rx))
        assertEquals(6, popcount(rw, qw))
        assertEquals(14, popcount(rw, qo))

        val query = fingerprint(listOf(qw, qo))
        val reference = fingerprint(listOf(rw, rx))

        assertTrue(
            DescriptorMatcher().match(query, reference).isEmpty(),
            "ratio 6/7 = 0.857 must reject the otherwise-mutual pair",
        )
        // Relaxing the threshold proves rejection came from the ratio test only.
        val relaxed = DescriptorMatcher(ratioThreshold = 0.9).match(query, reference)
        assertEquals(listOf(0 to 0), relaxed.map { it.queryIndex to it.referenceIndex })
        assertEquals(6, relaxed.single().hammingDistance)
    }

    @Test
    fun nonMutualBestPairsAreDropped() {
        // Two queries compete for reference X=0x00; reverse matching keeps only
        // the winner's pair. Reference Y=0x7B anchors its own query copy.
        val referenceX = descriptor(0x00)
        val referenceY = descriptor(0x7B)
        val strongQuery = descriptor(0x03) // dist 2 to X
        val weakQuery = descriptor(0x07) // dist 3 to X, second-best 5 to Y
        assertEquals(2, popcount(strongQuery, referenceX))
        assertEquals(3, popcount(weakQuery, referenceX))

        val matches = DescriptorMatcher().match(
            fingerprint(listOf(strongQuery, weakQuery, referenceY)),
            fingerprint(listOf(referenceX, referenceY)),
        )

        assertEquals(listOf(0 to 0, 2 to 1), matches.map { it.queryIndex to it.referenceIndex })
    }

    @Test
    fun profileMismatchIsRejectedBeforeAnyWork() {
        val query = fingerprint(listOf(descriptor(1)), profileId = "other-profile")
        val reference = fingerprint(listOf(descriptor(1)))

        assertFailsWith<IllegalArgumentException> {
            DescriptorMatcher().match(query, reference)
        }
    }

    @Test
    fun emptyInputsMatchNothing() {
        val empty = fingerprint(emptyList())
        val other = fingerprint(listOf(descriptor(1)))
        assertTrue(DescriptorMatcher().match(empty, other).isEmpty())
        assertTrue(DescriptorMatcher().match(other, empty).isEmpty())
    }

    @Test
    fun fewerThanTwoReferencesCanNeverPassTheRatioTest() {
        val matches = DescriptorMatcher().match(
            fingerprint(listOf(descriptor(0xAA), descriptor(0x55))),
            fingerprint(listOf(descriptor(0xAA))),
        )
        assertTrue(matches.isEmpty())
    }
}
