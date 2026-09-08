package dev.hryshyn.remanence.core.recognition

import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class SiftRootSiftFingerprintExtractorTest {

    private val extractor = SiftRootSiftFingerprintExtractor()
    private val width = 320
    private val height = 200

    @Test
    fun nativeExtractionIsRepeatableAndUsesRawP0Rows() {
        loadNativeOrSkip()
        val frame = texturedFrame()
        val first = extractor.extract(frame, width, height)
        val second = extractor.extract(frame.copyOf(), width, height)

        assertEquals(
            SiftRootSiftFingerprintCodec.PROFILE_ID,
            first.profileId,
        )
        assertTrue(first.keypoints.isNotEmpty())
        assertTrue(first.keypoints.size <= 1_500)
        assertEquals(first.keypoints.size, first.quantizedSiftDescriptors.size)
        assertTrue(
            SiftRootSiftFingerprintCodec.serialize(first)
                .contentEquals(SiftRootSiftFingerprintCodec.serialize(second)),
        )
        first.quantizedSiftDescriptors.forEach { row ->
            assertEquals(128, row.size)
            assertTrue(row.any { it.toInt() != 0 })
            assertTrue(row.all { (it.toInt() and 0xFF) in 0..255 })
        }
        assertTrue(
            SiftRootSiftFingerprintCodec.serialize(first)
                .contentEquals(SiftRootSiftFingerprintCodec.serialize(second)),
        )
    }

    @Test
    fun gridUsesHalfOpenCellsAndClampsExactOuterBoundaryToLastCell() {
        val selected = extractor.selectCandidates(
            width,
            height,
            listOf(
                candidate(0, x = 0.0, y = 0.0, response = 5.0),
                candidate(1, x = width / 6.0, y = height / 6.0, response = 4.0),
                candidate(2, x = width.toDouble(), y = height.toDouble(), response = 3.0),
            ),
        )

        assertEquals(listOf(0, 1, 2), selected.map { it.candidate.sourceIndex })
        assertEquals(0, selected[0].cellX)
        assertEquals(0, selected[0].cellY)
        assertEquals(1, selected[1].cellX)
        assertEquals(1, selected[1].cellY)
        assertEquals(5, selected[2].cellX)
        assertEquals(5, selected[2].cellY)
    }

    @Test
    fun eachCellRetainsOnlyTheHighest45Responses() {
        val candidates = List(46) { index ->
            candidate(
                index,
                x = 10.0 + index / 100.0,
                y = 10.0 + index / 100.0,
                response = index.toDouble(),
            )
        }

        val selected = extractor.selectCandidates(width, height, candidates)

        assertEquals(45, selected.size)
        assertTrue(selected.none { it.candidate.sourceIndex == 0 })
        assertEquals((45 downTo 1).toList(), selected.map { it.candidate.sourceIndex })
    }

    @Test
    fun globalCapIs1500AfterThe36Cell45PointSelection() {
        val candidates = buildList {
            var sourceIndex = 0
            for (cellY in 0 until 6) {
                for (cellX in 0 until 6) {
                    repeat(45) { rank ->
                        add(
                            candidate(
                                sourceIndex++,
                                x = (cellX + 0.25) * width / 6.0 + rank / 10_000.0,
                                y = (cellY + 0.25) * height / 6.0 + rank / 10_000.0,
                                response = (1_620 - sourceIndex).toDouble(),
                            ),
                        )
                    }
                }
            }
        }

        val selected = extractor.selectCandidates(width, height, candidates)

        assertEquals(1_500, selected.size)
        assertEquals((0 until 1_500).toSet(), selected.map { it.candidate.sourceIndex }.toSet())
    }

    @Test
    fun equalResponsesHaveACompleteDeterministicTieBreak() {
        val input = listOf(
            candidate(4, 2.0, 2.0, response = 1.0, size = 2.0),
            candidate(1, 1.0, 2.0, response = 1.0, size = 2.0),
            candidate(3, 2.0, 1.0, response = 1.0, size = 2.0),
            candidate(2, 1.0, 1.0, response = 1.0, size = 2.0),
        )

        val first = extractor.selectCandidates(width, height, input)
        val second = extractor.selectCandidates(width, height, input.reversed())

        assertEquals(first, second)
        assertEquals(listOf(2, 1, 3, 4), first.map { it.candidate.sourceIndex })
    }

    @Test
    fun invalidOrEmptyDetectionsAreHardFailuresAtExtractionBoundary() {
        assertFailsWith<IllegalArgumentException> {
            extractor.selectCandidates(width, height, emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            extractor.selectCandidates(
                width,
                height,
                listOf(candidate(0, 1.0, 1.0, response = Double.NaN)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            extractor.selectCandidates(
                width,
                height,
                listOf(candidate(0, -0.1, 1.0, response = 1.0)),
            )
        }
    }

    @Test
    fun inputDimensionsAreBoundedBeforeNativeAllocation() {
        assertFailsWith<IllegalArgumentException> {
            extractor.extract(IntArray(1), 0, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            extractor.extract(IntArray(1), 100_001, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            extractor.extract(IntArray(1), width, height)
        }
        assertFailsWith<IllegalArgumentException> {
            extractor.extract(IntArray(1), 4_001, 4_001)
        }
    }

    @Test
    fun mixedZeroAndUsableRowsRemainStorageValid() {
        val zero = ByteArray(128)
        val usable = ByteArray(128).also { it[0] = 1 }

        assertTrue(extractor.hasMatcherUsableDescriptorRows(listOf(zero, usable)))
        assertTrue(!extractor.hasMatcherUsableDescriptorRows(listOf(zero)))
        assertTrue(!extractor.hasMatcherUsableDescriptorRows(emptyList()))
    }

    @Test
    fun oversizedKeypointDiameterClampsScaleMetadata() {
        val selected = extractor.selectCandidates(
            width,
            height,
            listOf(candidate(0, 10.0, 10.0, response = 1.0, size = width * 2.0)),
        )

        assertEquals(
            SiftRootSiftFingerprintCodec.MICRO_UNITS,
            extractor.domainKeypointForTesting(selected.single().candidate, width, height).scaleMicro,
        )
    }

    @Test
    fun cleanupReleasesEveryOwnedObjectAndDoesNotMaskPrimaryFailure() {
        val released = ArrayList<String>()
        val primary = IllegalStateException("primary")

        extractor.cleanupForTesting(
            primaryFailure = primary,
            actions = listOf(
                { released += "sift"; error("clear failed") },
                { released += "descriptors" },
                { released += "selected" },
                { released += "detected" },
                { released += "mask" },
                { released += "gray" },
            ),
        )

        assertEquals(listOf("sift", "descriptors", "selected", "detected", "mask", "gray"), released)
        assertEquals(1, primary.suppressed.size)
        assertEquals("clear failed", primary.suppressed.single().message)
    }

    @Test
    fun cleanupFailureWipesDescriptorRowsBeforeOwnershipTransferFails() {
        val row = ByteArray(128) { 7 }

        assertFailsWith<IllegalStateException> {
            extractor.cleanupBeforeTransferForTesting(
                outputDescriptors = listOf(row),
                actions = listOf { throw IllegalStateException("clear failed") },
            )
        }

        assertTrue(row.all { it == 0.toByte() })
    }

    private fun loadNativeOrSkip() {
        runCatching { System.loadLibrary("opencv_java4100") }
            .onFailure { assumeTrue("desktop OpenCV natives unavailable: $it", false) }
    }

    private fun candidate(
        sourceIndex: Int,
        x: Double,
        y: Double,
        response: Double,
        size: Double = 4.0,
        angle: Double = 0.0,
        octave: Int = 0,
    ) = SiftRootSiftFingerprintExtractor.SiftCandidate(
        xPx = x,
        yPx = y,
        sizePx = size,
        angleDegrees = angle,
        response = response,
        encodedOctave = octave,
        sourceIndex = sourceIndex,
    )

    private fun texturedFrame(): IntArray {
        val random = Random(0x52656D616E656E63)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val base = (x * 255 / width)
                val noise = random.nextInt(96)
                val channel = (base + noise).coerceIn(0, 255)
                pixels[y * width + x] = 0xFF000000.toInt() or
                    (channel shl 16) or
                    (channel shl 8) or
                    ((channel * 3) % 256)
            }
        }
        return pixels
    }
}
