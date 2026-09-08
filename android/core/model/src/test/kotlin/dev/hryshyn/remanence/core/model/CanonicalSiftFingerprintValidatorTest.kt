package dev.hryshyn.remanence.core.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class CanonicalSiftFingerprintValidatorTest {
    @Test
    fun `accepts canonical bytes without changing the original array`() {
        val bytes = canonicalBytes()
        val original = bytes.copyOf()

        CanonicalSiftFingerprintValidator.requireCanonical(
            SiftRootSiftFingerprintCodec.PROFILE_ID,
            bytes,
        )

        assertContentEquals(original, bytes)
    }

    @Test
    fun `rejects an outer profile that does not match the canonical SIFT profile`() {
        assertFailsWith<IllegalArgumentException> {
            CanonicalSiftFingerprintValidator.requireCanonical(
                "retired-profile-v0",
                canonicalBytes(),
            )
        }
    }

    @Test
    fun `rejects noncanonical or malformed bytes`() {
        val bytes = canonicalBytes()
        assertFailsWith<IllegalArgumentException> {
            CanonicalSiftFingerprintValidator.requireCanonical(
                SiftRootSiftFingerprintCodec.PROFILE_ID,
                bytes + byteArrayOf(0x50, 0x01),
            )
        }
    }

    private fun canonicalBytes(): ByteArray {
        val fingerprint = SiftRootSiftFingerprint(
            profileId = SiftRootSiftFingerprintCodec.PROFILE_ID,
            canonicalWidthPx = 1600,
            canonicalHeightPx = 1000,
            coarseHash64 = 7L,
            keypoints = listOf(
                SiftRootSiftKeypoint(
                    xMicro = 125_000,
                    yMicro = 250_000,
                    scaleMicro = 1_000_000,
                    angleCentiDegrees = 0,
                    responseQuantized = 1,
                    octave = 0,
                ),
            ),
            quantizedSiftDescriptors = listOf(ByteArray(SiftRootSiftFingerprintCodec.DESCRIPTOR_BYTES) { it.toByte() }),
        )
        return try {
            SiftRootSiftFingerprintCodec.serialize(fingerprint)
        } finally {
            fingerprint.wipe()
        }
    }
}
