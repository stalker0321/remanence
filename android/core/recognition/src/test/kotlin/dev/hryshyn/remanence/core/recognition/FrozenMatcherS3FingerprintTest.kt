package dev.hryshyn.remanence.core.recognition

import dev.hryshyn.remanence.core.model.FrontFingerprintCodecs
import dev.hryshyn.remanence.core.model.FrozenMatcherS3FingerprintCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrozenMatcherS3FingerprintTest {
    private fun features(n: Int, w: Int = 518, h: Int = 371) = FrozenMatcherS3.Features(
        xy = FloatArray(2 * n) { i -> if (i % 2 == 0) (i * 7 % w).toFloat() + .25f else (i * 3 % h).toFloat() + .5f },
        descriptors = ByteArray(128 * n) { (it * 31).toByte() },
        width = w,
        height = h,
    )

    @Test fun senderFeaturesRoundTripThroughTheS3Codec() {
        val original = features(1620)
        val bytes = FrozenMatcherS3FingerprintCodec.serialize(FrozenMatcherS3Fingerprint.toFingerprint(original))
        assertEquals(FrozenMatcherS3FingerprintCodec.PROFILE_ID, FrontFingerprintCodecs.profileOf(bytes))
        val parsed = FrontFingerprintCodecs.parse(bytes)
        assertTrue(FrozenMatcherS3Fingerprint.isS3(parsed))
        assertNull(FrozenMatcherS3Fingerprint.queryFrame(parsed))
        val back = FrozenMatcherS3Fingerprint.features(parsed)
        assertEquals(original.count, back.count)
        assertTrue(original.descriptors.contentEquals(back.descriptors))
        for (i in original.xy.indices) assertEquals(original.xy[i], back.xy[i], 0.001f)
    }

    @Test fun recipientQueryKeepsItsFramePlacement() {
        val frame = FrozenMatcherS3Fingerprint.QueryFrame(originX = 37, originY = 120, frameWidth = 374, frameHeight = 561, components = 2)
        val bytes = FrozenMatcherS3FingerprintCodec.serialize(FrozenMatcherS3Fingerprint.toFingerprint(features(10, 300, 200), frame))
        val parsed = FrontFingerprintCodecs.parse(bytes)
        assertEquals(frame, FrozenMatcherS3Fingerprint.queryFrame(parsed))
        val back = FrozenMatcherS3Fingerprint.features(parsed)
        assertEquals(37, back.originX)
        assertEquals(561, back.frameHeight)
    }
}
