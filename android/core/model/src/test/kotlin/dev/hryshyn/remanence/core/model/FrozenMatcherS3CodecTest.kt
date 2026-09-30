package dev.hryshyn.remanence.core.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FrozenMatcherS3CodecTest {

    @Test
    fun versionAndProfileTagsAreNewAndExact() {
        assertEquals(4, FrozenMatcherS3FingerprintCodec.FORMAT_VERSION)
        assertEquals("postcard-frozen-matcher-s3", FrozenMatcherS3FingerprintCodec.PROFILE_ID)
        assertTrueDistinctFromV1()
    }

    private fun assertTrueDistinctFromV1() {
        kotlin.test.assertTrue(FrozenMatcherS3FingerprintCodec.FORMAT_VERSION != SiftRootSiftFingerprintCodec.FORMAT_VERSION)
        kotlin.test.assertTrue(FrozenMatcherS3FingerprintCodec.PROFILE_ID != SiftRootSiftFingerprintCodec.PROFILE_ID)
    }

    @Test
    fun roundTripPreservesCanonicalBytes() {
        val bytes = s3Bytes()
        val parsed = FrozenMatcherS3FingerprintCodec.parse(bytes)
        try {
            assertEquals(FrozenMatcherS3FingerprintCodec.PROFILE_ID, parsed.profileId)
            assertEquals(1600, parsed.canonicalWidthPx)
        } finally {
            parsed.wipe()
        }
        assertContentEquals(bytes, FrozenMatcherS3FingerprintCodec.serialize(parsedValue()))
    }

    @Test
    fun oldCodecRejectsNewBytes() {
        val s3 = s3Bytes()
        val failure = try {
            SiftRootSiftFingerprintCodec.parse(s3)
            null
        } catch (_: IllegalArgumentException) {
            "rejected"
        } finally {
            s3.fill(0)
        }
        assertEquals("rejected", failure)
    }

    @Test
    fun newCodecRejectsV1Bytes() {
        val v1 = SiftRootSiftFingerprintCodec.serialize(v1Value())
        try {
            assertFailsWith<IllegalArgumentException> {
                FrozenMatcherS3FingerprintCodec.parse(v1)
            }
        } finally {
            v1.fill(0)
        }
    }

    @Test
    fun serializeRejectsForeignProfileId() {
        assertFailsWith<IllegalArgumentException> {
            FrozenMatcherS3FingerprintCodec.serialize(parsedValue().copyProfile(SiftRootSiftFingerprintCodec.PROFILE_ID))
        }
    }

    @Test
    fun malformedInputsFailClosed() {
        assertFailsWith<IllegalArgumentException> {
            FrozenMatcherS3FingerprintCodec.parse(ByteArray(0))
        }
        assertFailsWith<IllegalArgumentException> {
            FrozenMatcherS3FingerprintCodec.parse(byteArrayOf(1, 2, 3, 4))
        }
        val truncated = s3Bytes().copyOf(24)
        try {
            assertFailsWith<IllegalArgumentException> {
                FrozenMatcherS3FingerprintCodec.parse(truncated)
            }
        } finally {
            truncated.fill(0)
        }
        val noncanonical = s3Bytes() + byteArrayOf(0x50.toByte(), 0x01)
        try {
            assertFailsWith<IllegalArgumentException> {
                FrozenMatcherS3FingerprintCodec.parse(noncanonical)
            }
        } finally {
            noncanonical.fill(0)
        }
    }

    private fun parsedValue(): SiftRootSiftFingerprint = SiftRootSiftFingerprint(
        profileId = FrozenMatcherS3FingerprintCodec.PROFILE_ID,
        canonicalWidthPx = 1600,
        canonicalHeightPx = 1000,
        coarseHash64 = 7L,
        keypoints = listOf(
            SiftRootSiftKeypoint(
                xMicro = 125_000,
                yMicro = 250_000,
                scaleMicro = 500_000,
                angleCentiDegrees = 9_000,
                responseQuantized = 10_000,
                octave = 0,
            ),
        ),
        quantizedSiftDescriptors = listOf(ByteArray(128) { it.toByte() }),
    )

    private fun s3Bytes(): ByteArray =
        FrozenMatcherS3FingerprintCodec.serialize(parsedValue())

    private fun v1Value(): SiftRootSiftFingerprint = SiftRootSiftFingerprint(
        profileId = SiftRootSiftFingerprintCodec.PROFILE_ID,
        canonicalWidthPx = 1600,
        canonicalHeightPx = 1000,
        coarseHash64 = 7L,
        keypoints = listOf(
            SiftRootSiftKeypoint(
                xMicro = 125_000,
                yMicro = 250_000,
                scaleMicro = 500_000,
                angleCentiDegrees = 9_000,
                responseQuantized = 10_000,
                octave = 0,
            ),
        ),
        quantizedSiftDescriptors = listOf(ByteArray(128) { it.toByte() }),
    )

    private fun SiftRootSiftFingerprint.copyProfile(profileId: String): SiftRootSiftFingerprint =
        SiftRootSiftFingerprint(
            profileId = profileId,
            canonicalWidthPx = canonicalWidthPx,
            canonicalHeightPx = canonicalHeightPx,
            coarseHash64 = coarseHash64,
            keypoints = keypoints,
            quantizedSiftDescriptors = quantizedSiftDescriptors,
        )
}
