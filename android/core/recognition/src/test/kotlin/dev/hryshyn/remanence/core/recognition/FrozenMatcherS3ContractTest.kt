package dev.hryshyn.remanence.core.recognition

import dev.hryshyn.remanence.core.model.FrozenMatcherS3FingerprintCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrozenMatcherS3ContractTest {

    @Test
    fun pinsMatchTheFrozenBriefExactly() {
        assertEquals("postcard-frozen-matcher-s3", FrozenMatcherS3Contract.S3_PROFILE_ID)
        assertEquals(2, FrozenMatcherS3Contract.S3_FORMAT_VERSION)
        assertEquals(1024, FrozenMatcherS3Contract.VOCABULARY_WORDS)
        assertEquals(8, FrozenMatcherS3Contract.RETRIEVAL_TOP_K)
        assertEquals("MAGSAC", FrozenMatcherS3Contract.ESTIMATOR_ID)
        assertEquals(12.0, FrozenMatcherS3Contract.BASELINE_MIN_SCORE)
        assertEquals(1.6, FrozenMatcherS3Contract.BASELINE_MIN_RUNNER_UP_MARGIN)
        assertEquals(12, FrozenMatcherS3Contract.BASELINE_MIN_INLIERS)
        assertEquals(0.04, FrozenMatcherS3Contract.BASELINE_MIN_COVERAGE)
        assertEquals(0.30, FrozenMatcherS3Contract.BASELINE_MIN_INLIER_RATIO)
        assertEquals(
            "e8b264cf08f1925f24e5996af15268adbce8e8daa9b0aa7b6351bd1be865f45d",
            FrozenMatcherS3Contract.ENROLLMENT_FINGERPRINT_HEX,
        )
        assertEquals(
            "7f332dfd4bf55c26fad00e23d46e111871aadeeedeb0872ce708168e43d28855",
            FrozenMatcherS3Contract.INDEX_FINGERPRINT_HEX,
        )
    }

    @Test
    fun s3ProfileIsIsolatedFromV1() {
        assertTrue(FrozenMatcherS3Contract.S3_PROFILE_ID != RecognitionProfile.SIFT_ROOTSIFT_V1_ID)
        assertTrue(FrozenMatcherS3Contract.S3_FORMAT_VERSION != RecognitionProfile.FORMAT_VERSION_1)
        assertTrue(FrozenMatcherS3Contract.S3_FORMAT_VERSION != FrozenMatcherS3FingerprintCodec.FORMAT_VERSION)
        assertEquals(FrozenMatcherS3Contract.S3_PROFILE_ID, FrozenMatcherS3FingerprintCodec.PROFILE_ID)
    }

    @Test
    fun baselineAcceptsExactlyAtTheFrozenFloors() {
        assertTrue(
            FrozenMatcherS3Contract.passesBaseline(
                FrozenMatcherS3Contract.BaselineObservation(
                    score = 12.0,
                    runnerUpMargin = 1.6,
                    inliers = 12,
                    coverage = 0.04,
                    inlierRatio = 0.30,
                ),
            ),
        )
    }

    @Test
    fun baselineRejectsEachFloorViolation() {
        val atFloors = FrozenMatcherS3Contract.BaselineObservation(
            score = 12.0,
            runnerUpMargin = 1.6,
            inliers = 12,
            coverage = 0.04,
            inlierRatio = 0.30,
        )
        assertFalse(FrozenMatcherS3Contract.passesBaseline(atFloors.copy(score = 11.9)))
        assertFalse(FrozenMatcherS3Contract.passesBaseline(atFloors.copy(runnerUpMargin = 1.5)))
        assertFalse(FrozenMatcherS3Contract.passesBaseline(atFloors.copy(inliers = 11)))
        assertFalse(FrozenMatcherS3Contract.passesBaseline(atFloors.copy(coverage = 0.039)))
        assertFalse(FrozenMatcherS3Contract.passesBaseline(atFloors.copy(inlierRatio = 0.29)))
        assertFalse(FrozenMatcherS3Contract.passesBaseline(atFloors.copy(score = Double.NaN)))
        assertFalse(
            FrozenMatcherS3Contract.passesBaseline(atFloors.copy(coverage = Double.POSITIVE_INFINITY)),
        )
    }

    @Test
    fun indexBuildLoadRoundTrips() {
        val entries = listOf(
            FrozenMatcherS3Contract.IndexEntry(
                FrozenMatcherS3Contract.S3_PROFILE_ID,
                "card-004",
                byteArrayOf(1, 2, 3),
            ),
        )
        val header = FrozenMatcherS3Contract.loadIndex(FrozenMatcherS3Contract.buildIndex(entries))
        assertEquals(FrozenMatcherS3Contract.S3_FORMAT_VERSION, header.formatVersion)
        assertEquals(FrozenMatcherS3Contract.S3_PROFILE_ID, header.profileId)
        assertEquals(FrozenMatcherS3Contract.ENROLLMENT_FINGERPRINT_HEX, header.enrollmentFingerprintHex)
        assertEquals(FrozenMatcherS3Contract.INDEX_FINGERPRINT_HEX, header.indexFingerprintHex)
        assertEquals(1, header.entryCount)
    }

    @Test
    fun indexBuildRejectsMixedProfileEntries() {
        assertFailsWith<IllegalArgumentException> {
            FrozenMatcherS3Contract.buildIndex(
                listOf(
                    FrozenMatcherS3Contract.IndexEntry(
                        RecognitionProfile.SIFT_ROOTSIFT_V1_ID,
                        "card-004",
                        byteArrayOf(1),
                    ),
                ),
            )
        }
    }

    @Test
    fun indexLoadFailsClosedOnTampering() {
        val valid = FrozenMatcherS3Contract.buildIndex(
            listOf(
                FrozenMatcherS3Contract.IndexEntry(
                    FrozenMatcherS3Contract.S3_PROFILE_ID,
                    "card-004",
                    byteArrayOf(1),
                ),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            FrozenMatcherS3Contract.loadIndex(valid.copyOf().also { it[0] = 0x00 })
        }
        assertFailsWith<IllegalArgumentException> {
            FrozenMatcherS3Contract.loadIndex(valid + byteArrayOf(0x00))
        }
        assertFailsWith<IllegalArgumentException> {
            FrozenMatcherS3Contract.loadIndex(byteArrayOf(1, 2, 3))
        }
        assertFailsWith<IllegalArgumentException> {
            FrozenMatcherS3Contract.loadIndex(ByteArray(0))
        }
    }

    @Test
    fun indexLoadFailsClosedOnFingerprintMismatch() {
        val other = "0".repeat(63) + "1"
        val built = FrozenMatcherS3Contract.buildIndex(
            entries = listOf(
                FrozenMatcherS3Contract.IndexEntry(
                    FrozenMatcherS3Contract.S3_PROFILE_ID,
                    "card-004",
                    byteArrayOf(1),
                ),
            ),
            indexFingerprintHex = other,
        )
        val failure = try {
            FrozenMatcherS3Contract.loadIndex(built)
            null
        } catch (_: IllegalArgumentException) {
            "mismatch"
        }
        assertEquals("mismatch", failure)
    }

    @Test
    fun queryGuardRejectsNonS3Rows() {
        FrozenMatcherS3Contract.requireS3Row(FrozenMatcherS3Contract.S3_PROFILE_ID)
        assertFailsWith<IllegalArgumentException> {
            FrozenMatcherS3Contract.requireS3Row(RecognitionProfile.SIFT_ROOTSIFT_V1_ID)
        }
    }
}
