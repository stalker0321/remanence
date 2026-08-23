package postmark.core.recognition

import app.postmark.recognition.v1.PostcardFingerprint as FingerprintWire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FingerprintCodecTest {

    private fun keypoint(i: Int) = FingerprintKeypoint(
        xNormalized = (i % 1000) / 1000.0,
        yNormalized = ((i * 7) % 1000) / 1000.0,
        scaleNormalized = 0.001 * (i + 1),
        angleCentiDegrees = (i * 13) % 36000,
        responseQuantized = i,
        octave = ((i % 17) - 8).coerceIn(-8, 8),
    )

    private fun descriptor(i: Int): ByteArray = ByteArray(32) { ((it + i) and 0xFF).toByte() }

    private fun fingerprint(keypoints: Int = 5, side: FingerprintSide = FingerprintSide.FRONT) =
        PostcardFingerprint(
            profileId = "mvp-orb-v1",
            side = side,
            canonicalWidthPx = 1600,
            canonicalHeightPx = 1000,
            coarseHash64 = 0x1122334455667788L,
            keypoints = List(keypoints) { keypoint(it) },
            descriptors = List(keypoints) { descriptor(it) },
            quality = ExtractionQuality(
                blurScore = 120.5,
                exposureScore = 88.25,
                glareFraction = 0.031,
                detectedAreaRatio = 0.77,
            ),
        )

    @Test
    fun serializeParseRoundTripsAllFields() {
        val original = fingerprint()
        val parsed = FingerprintCodec.parse(FingerprintCodec.serialize(original))

        assertEquals(original.profileId, parsed.profileId)
        assertEquals(original.side, parsed.side)
        assertEquals(original.canonicalWidthPx, parsed.canonicalWidthPx)
        assertEquals(original.coarseHash64, parsed.coarseHash64)
        assertEquals(original.keypoints.size, parsed.keypoints.size)
        // Quantized fields survive exactly; normalized doubles within micro tolerance.
        original.keypoints.zip(parsed.keypoints).forEachIndexed { index, (a, b) ->
            assertTrue(kotlin.math.abs(a.xNormalized - b.xNormalized) < 1e-6, "x@$index")
            assertTrue(kotlin.math.abs(a.scaleNormalized - b.scaleNormalized) < 1e-6, "scale@$index")
            assertEquals(a.angleCentiDegrees, b.angleCentiDegrees)
            assertEquals(a.octave, b.octave)
        }
        original.descriptors.forEachIndexed { i, d ->
            assertTrue(d.contentEquals(parsed.descriptors[i]))
        }
    }

    @Test
    fun serializedSizeIsBoundedAndCompact() {
        val bytes = FingerprintCodec.serialize(fingerprint(150))
        // ~1500 max keypoints must stay well under the 1 MiB manifest bound.
        assertTrue(bytes.size in 1..(150 * 60 + 200))
    }

    @Test
    fun misalignedDescriptorsFailClosed() {
        val base = fingerprint()
        val bad = PostcardFingerprint(
            base.profileId,
            base.side,
            base.canonicalWidthPx,
            base.canonicalHeightPx,
            base.coarseHash64,
            base.keypoints,
            listOf(base.descriptors[0]),
            base.quality,
        )
        assertFailsWith<IllegalArgumentException> { FingerprintCodec.serialize(bad) }
    }

    @Test
    fun shortDescriptorRowFailsClosed() {
        val base = fingerprint()
        val bad = PostcardFingerprint(
            base.profileId, base.side, base.canonicalWidthPx, base.canonicalHeightPx,
            base.coarseHash64,
            base.keypoints,
            base.descriptors.mapIndexed { i, d -> if (i == 2) d.copyOf(31) else d },
            base.quality,
        )
        assertFailsWith<IllegalArgumentException> { FingerprintCodec.serialize(bad) }
    }

    @Test
    fun keypointCapExceededFailsClosed() {
        val tooMany = fingerprint(1501)
        assertFailsWith<IllegalArgumentException> { FingerprintCodec.serialize(tooMany) }
    }

    @Test
    fun truncatedPayloadFailsClosed() {
        val bytes = FingerprintCodec.serialize(fingerprint(20))
        for (cut in intArrayOf(0, 1, bytes.size / 2)) {
            assertFailsWith<Exception>("cut=$cut") { FingerprintCodec.parse(bytes.copyOf(cut)) }
        }
    }

    @Test
    fun trailingGarbageFailsClosed() {
        val bytes = FingerprintCodec.serialize(fingerprint())
        assertFailsWith<Exception> { FingerprintCodec.parse(bytes + byteArrayOf(0x7F)) }
    }

    @Test
    fun wireWithUnknownVersionOrUnspecifiedSideFailsClosed() {
        val wire = FingerprintWire.newBuilder()
            .setFormatVersion(99)
            .setRecognitionProfileId("mvp-orb-v1")
            .setSide(FingerprintWire.Side.FRONT)
            .setCanonicalWidthPx(1600)
            .setCanonicalHeightPx(1000)
            .setCoarseHash64(1L)
            .setExtractionQuality(FingerprintWire.ExtractionQuality.getDefaultInstance())
            .build()
        assertFailsWith<IllegalArgumentException> { FingerprintCodec.parse(wire.toByteArray()) }

        val noSide = FingerprintWire.newBuilder()
            .setFormatVersion(1)
            .setRecognitionProfileId("mvp-orb-v1")
            .setCanonicalWidthPx(1600)
            .setCanonicalHeightPx(1000)
            .build()
        assertFailsWith<IllegalArgumentException> { FingerprintCodec.parse(noSide.toByteArray()) }
    }
}
