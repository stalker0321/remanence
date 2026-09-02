package dev.hryshyn.remanence.core.recognition

import java.util.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Proves the full normalized-fingerprint wire path: extracted fingerprints
 * serialize compactly and parse back with EXACT quantized coordinates and
 * descriptors (docs/recognition.md sections 6 and 14).
 */
class NormalizedFingerprintSerializationTest {

    private val profile = RecognitionProfile.mvpOrbV1()
    private val extractor = FingerprintExtractor(profile)
    private val w = 320
    private val h = 200

    @BeforeTest
    fun loadNative() {
        runCatching { System.loadLibrary("opencv_java4100") }
            .onFailure { assumeTrue("desktop OpenCV natives unavailable: $it", false) }
    }

    private fun texturedFrame(): IntArray {
        val random = Random(0x50574D41524B)
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val base = (x * 255 / w)
                val noise = random.nextInt(96)
                val channel = (base + noise).coerceIn(0, 255)
                pixels[y * w + x] =
                    0xFF000000.toInt() or (channel shl 16) or (channel shl 8) or ((channel * 3) % 256)
            }
        }
        return pixels
    }

    @Test
    fun frontAndBackSerializeCompactAndParseBackExactly() {
        // FRONT-only contract (ADR-012): only FRONT is a production fingerprint.
        val frontBytes = FingerprintCodec.serialize(extractor.extract(texturedFrame(), w, h, FingerprintSide.FRONT))

        // Raw serialized FRONT must sit far below the encrypted-manifest budget (1 MiB).
        assertTrue(frontBytes.size < 256 * 1024, "front=${frontBytes.size}")

        val parsedFront = FingerprintCodec.parse(frontBytes)
        assertEquals(FingerprintSide.FRONT, parsedFront.side)

        val originalFront = extractor.extract(texturedFrame(), w, h, FingerprintSide.FRONT)
        assertEquals(originalFront.keypoints.size, parsedFront.keypoints.size)
        originalFront.keypoints.zip(parsedFront.keypoints).forEach { (a, b) ->
            // Micro-quantized coordinates survive exactly.
            assertEquals(
                Math.round(a.xNormalized * FingerprintCodec.MICRO_UNITS),
                Math.round(b.xNormalized * FingerprintCodec.MICRO_UNITS),
            )
            assertEquals(
                Math.round(a.yNormalized * FingerprintCodec.MICRO_UNITS),
                Math.round(b.yNormalized * FingerprintCodec.MICRO_UNITS),
            )
            assertEquals(a.angleCentiDegrees, b.angleCentiDegrees)
        }
        originalFront.descriptors.forEachIndexed { i, d ->
            assertTrue(d.contentEquals(parsedFront.descriptors[i]))
        }
        // BACK attempt must fail closed in FRONT-only contract.
        val backFingerprint = extractor.extract(texturedFrame(), w, h, FingerprintSide.BACK)
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            FingerprintCodec.serialize(backFingerprint)
        }
    }

    @Test
    fun sideDistinctionSurvivesRoundTrip() {
        val front = FingerprintCodec.serialize(extractor.extract(texturedFrame(), w, h, FingerprintSide.FRONT))
        assertEquals(FingerprintSide.FRONT, FingerprintCodec.parse(front).side)
        // BACK round-trip no longer exists; verify that BACK wire is rejected.
        val backWire = dev.hryshyn.remanence.recognition.v1.PostcardFingerprint.newBuilder()
            .setFormatVersion(FingerprintCodec.FORMAT_VERSION)
            .setRecognitionProfileId(RecognitionProfile.MVP_ORB_V1_ID)
            .setSide(dev.hryshyn.remanence.recognition.v1.PostcardFingerprint.Side.BACK)
            .setCanonicalWidthPx(1600)
            .setCanonicalHeightPx(1000)
            .setCoarseHash64(1L)
            .setExtractionQuality(dev.hryshyn.remanence.recognition.v1.PostcardFingerprint.ExtractionQuality.getDefaultInstance())
            .build().toByteArray()
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            FingerprintCodec.parse(backWire)
        }
    }
}
