package postmark.core.recognition

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
        val frontBytes = FingerprintCodec.serialize(extractor.extract(texturedFrame(), w, h, FingerprintSide.FRONT))
        val backBytes = FingerprintCodec.serialize(extractor.extract(texturedFrame(), w, h, FingerprintSide.BACK))

        // Raw serialized fingerprints must sit far below the encrypted-manifest budget (1 MiB).
        assertTrue(frontBytes.size < 256 * 1024, "front=${frontBytes.size}")
        assertTrue(backBytes.size < 256 * 1024, "back=${backBytes.size}")

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
    }

    @Test
    fun sideDistinctionSurvivesRoundTrip() {
        val front = FingerprintCodec.serialize(extractor.extract(texturedFrame(), w, h, FingerprintSide.FRONT))
        val back = FingerprintCodec.serialize(extractor.extract(texturedFrame(), w, h, FingerprintSide.BACK))
        assertEquals(FingerprintSide.FRONT, FingerprintCodec.parse(front).side)
        assertEquals(FingerprintSide.BACK, FingerprintCodec.parse(back).side)
    }
}
