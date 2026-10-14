package dev.hryshyn.remanence.probe

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec
import dev.hryshyn.remanence.core.recognition.CandidateOrigin
import dev.hryshyn.remanence.core.recognition.IndexedCandidate
import dev.hryshyn.remanence.core.recognition.LocalMatchEngine
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.core.recognition.SiftRootSiftMatchFailure
import dev.hryshyn.remanence.core.recognition.SiftRootSiftMatcher
import dev.hryshyn.remanence.core.recognition.VisualScanResult
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/**
 * T186 native visual-recognition gate (device only — root runs it, no
 * JVM claim). Proves the recognition prerequisite with the actual
 * production matcher and profile — nothing else:
 *
 * - the historical saturating FRONT (every descriptor byte 255) gets
 *   NO_RATIO_MATCHES and no visual acceptance (regression negative —
 *   the defect was test data, never a matcher gate to weaken);
 * - the corrected modulo-256 FRONT self-match yields genuine
 *   reciprocal matches, RANSAC inliers and a homography, and
 *   [LocalMatchEngine.evaluateVisual] accepts a singleton candidate.
 *
 * Visual-only: verifier/grant ports THROW if touched, and neither
 * `run` nor `completeVisual` is invoked — this proves recognition
 * ONLY, never authenticated admission, grant, claim, open, or
 * retirement. No AppContainer, account, network, publication, OTP, or
 * secrets anywhere; failures name coarse phases only. All parsed
 * models and fixture bytes are wiped in `finally` on every outcome.
 */
@RunWith(AndroidJUnit4::class)
class PendingNativeFrontMatchTest {

    companion object {
        private val capsuleUuid = UUID.fromString("5f111111-2222-4333-8444-555555555555")

        @JvmStatic
        @BeforeClass
        fun loadNative() {
            assertTrue(
                "libopencv_java4 failed to load from the test process",
                OpenCVLoader.initDebug(),
            )
        }

        private fun visualEngine(): LocalMatchEngine = LocalMatchEngine(
            profile = RecognitionProfile.postcardSiftRootSiftV1(),
            verifier = {
                throw AssertionError("visual-only: verifier must not run")
            },
            grantIssuer = {
                throw AssertionError("visual-only: grant must not issue")
            },
        )
    }

    @Test
    fun saturatingFixtureHasNoRatioMatchesAndNoVisualAcceptance(): Unit = runBlocking {
        val queryBytes = CanonicalFrontFixture.saturatingFront()
        val referenceBytes = CanonicalFrontFixture.saturatingFront()
        try {
            val query = SiftRootSiftFingerprintCodec.parse(queryBytes)
            val reference = SiftRootSiftFingerprintCodec.parse(referenceBytes)
            try {
                val result = SiftRootSiftMatcher().match(query, reference)
                assertTrue(
                    "saturated rows must fail the ratio gate",
                    result.matches.isEmpty(),
                )
                assertEquals(
                    SiftRootSiftMatchFailure.NO_RATIO_MATCHES,
                    result.diagnostics.failure,
                )
                val visual = visualEngine().evaluateVisual(
                    query,
                    listOf(IndexedCandidate(capsuleUuid, reference, recipientPreferred = true)),
                )
                assertTrue(
                    "saturated rows must never visually accept",
                    visual is VisualScanResult.RecaptureRequired,
                )
            } finally {
                query.wipe()
                reference.wipe()
            }
        } finally {
            queryBytes.fill(0)
            referenceBytes.fill(0)
        }
    }

    @Test
    fun correctedFixtureSelfMatchIsGenuineAndAccepted(): Unit = runBlocking {
        val queryBytes = CanonicalFrontFixture.distinctFront()
        val referenceBytes = CanonicalFrontFixture.distinctFront()
        try {
            val query = SiftRootSiftFingerprintCodec.parse(queryBytes)
            val reference = SiftRootSiftFingerprintCodec.parse(referenceBytes)
            try {
                val result = SiftRootSiftMatcher().match(query, reference)
                assertTrue(
                    "corrected rows must reciprocally match",
                    result.matches.isNotEmpty(),
                )
                assertTrue(
                    "matches must be mutual nearest neighbours",
                    result.matches.all { it.queryIndex == it.referenceIndex },
                )
                assertTrue(
                    "RANSAC must keep inliers",
                    result.inlierMatchIndices.isNotEmpty(),
                )
                assertTrue(
                    "a homography must be observable",
                    result.homographyRowMajor != null,
                )
                assertEquals(
                    SiftRootSiftMatchFailure.NONE,
                    result.diagnostics.failure,
                )
                assertTrue(result.diagnostics.geometryAccepted)
                assertTrue(
                    result.diagnostics.referenceConvexHullCoverage > 0.0,
                )
                val visual = visualEngine().evaluateVisual(
                    query,
                    listOf(IndexedCandidate(capsuleUuid, reference, recipientPreferred = true)),
                )
                assertTrue(
                    "corrected self-match must visually accept",
                    visual is VisualScanResult.Accepted,
                )
                val accepted = visual as VisualScanResult.Accepted
                assertEquals(capsuleUuid, accepted.capsuleId)
                assertEquals(CandidateOrigin.RECIPIENT_PREFERRED, accepted.origin)
            } finally {
                query.wipe()
                reference.wipe()
            }
        } finally {
            queryBytes.fill(0)
            referenceBytes.fill(0)
        }
    }
}
