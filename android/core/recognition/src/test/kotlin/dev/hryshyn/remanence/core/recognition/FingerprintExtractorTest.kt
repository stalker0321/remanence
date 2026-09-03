package dev.hryshyn.remanence.core.recognition

import java.util.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class FingerprintExtractorTest {

    private val profile = RecognitionProfile.mvpOrbV1()
    private val extractor = FingerprintExtractor(profile)
    private val w = 320
    private val h = 200

    @BeforeTest
    fun loadNative() {
        runCatching { System.loadLibrary("opencv_java4100") }
            .onFailure { assumeTrue("desktop OpenCV natives unavailable: $it", false) }
    }

    /** Deterministic textured fixture: seeded noise over gradients. */
    private fun texturedFrame(): IntArray {
        val random = Random(0x52656D616E656E63) // "Remanenc"
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val base = (x * 255 / w)
                val noise = random.nextInt(96)
                val channel = (base + noise).coerceIn(0, 255)
                pixels[y * w + x] = 0xFF000000.toInt() or (channel shl 16) or (channel shl 8) or ((channel * 3) % 256)
            }
        }
        return pixels
    }

    @Test
    fun extractionIsDeterministicForIdenticalInput() {
        val frame = texturedFrame()
        val first = FingerprintCodec.serialize(extractor.extract(frame, w, h))
        val second = FingerprintCodec.serialize(extractor.extract(frame.copyOf(), w, h))
        assertTrue(first.contentEquals(second), "same input must serialize identically")
    }

    @Test
    fun fingerprintHonorsBoundsAndAlignment() {
        val fingerprint = extractor.extract(texturedFrame(), w, h)
        assertEquals("mvp-orb-v1", fingerprint.profileId)
        assertTrue(fingerprint.keypoints.isNotEmpty())
        assertTrue(fingerprint.keypoints.size <= profile.orb.nfeatures)
        assertEquals(fingerprint.keypoints.size, fingerprint.descriptors.size)
        fingerprint.descriptors.forEach { assertEquals(32, it.size) }
        for (keypoint in fingerprint.keypoints) {
            assertTrue(keypoint.xNormalized in 0.0..1.0)
            assertTrue(keypoint.yNormalized in 0.0..1.0)
        }
    }

    @Test
    fun deduplicationEnforcesMinimumDistanceAndCap() {
        // 32 clusters of two points 0.5px apart collapse into 32 kept points.
        val dense = List(64) { i ->
            val cluster = i / 2
            extractor.probe(
                x = cluster * 5f + if (i % 2 == 0) 0f else 0.5f,
                y = cluster * 7f,
                response = 200f - cluster,
            )
        }
        assertEquals(32, extractor.deduplicateAndCap(dense).size)
    }

    @Test
    fun capLimitsKeptPointsToNfeatures() {
        val spread = List(4000) { i ->
            extractor.probe(
                x = (i % 200) * 1.5f,
                y = (i * 7 % 1000) * 0.2f,
                response = 5000f - i,
            )
        }
        val kept = extractor.deduplicateAndCap(spread)
        assertTrue(kept.size <= profile.orb.nfeatures)
    }
}
