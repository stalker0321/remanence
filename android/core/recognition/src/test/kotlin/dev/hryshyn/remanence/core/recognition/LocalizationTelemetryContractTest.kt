package dev.hryshyn.remanence.core.recognition

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalizationTelemetryContractTest {
    @Test
    fun recognitionTelemetryContainsOnlyRedactedNumericFields() {
        val summary = MatchDiagnosticEvent(
            phase = MatchDiagnosticPhase.CANDIDATE_EVALUATED,
            origin = CandidateOrigin.RECIPIENT_PREFERRED,
            candidateCount = 3,
            score = 0.8123,
            margin = 0.1200,
            ratioMutualMatches = 24,
            ransacInliers = 19,
            coverage = 0.4567,
        ).safeSummary()

        assertTrue(summary.contains("candidates=3"))
        assertTrue(summary.contains("score=0.8123"))
        assertTrue(summary.contains("inliers=19"))
        assertTrue(summary.contains("coverage=0.4567"))
        listOf("bitmap", "descriptor", "coordinate", "handle", "capsule", "chooser").forEach {
            assertFalse(summary.contains(it, ignoreCase = true), "telemetry leaked $it")
        }
    }

    @Test
    fun resultTelemetryLabelsGrantAndNoMatchWithoutIdentifiers() {
        val grant = MatchDiagnosticEvent.result(
            outcome = MatchDiagnosticOutcome.GRANT,
            origin = CandidateOrigin.SENDER_FALLBACK,
        ).safeSummary()
        val noMatch = MatchDiagnosticEvent.result(MatchDiagnosticOutcome.NO_MATCH).safeSummary()

        assertTrue(grant.contains("outcome=GRANT"))
        assertTrue(noMatch.contains("outcome=NO_MATCH"))
        listOf("capsuleId", "candidateId", "identifier", "uuid", "handle").forEach {
            assertFalse(grant.contains(it, ignoreCase = true))
            assertFalse(noMatch.contains(it, ignoreCase = true))
        }
    }

    @Test
    fun indexAndMatcherTelemetryExposeOnlySafeCountsAndGates() {
        val summary = MatchDiagnosticEvent(
            phase = MatchDiagnosticPhase.INDEX,
            outcome = MatchDiagnosticOutcome.INDEX_UNAVAILABLE,
            candidateCount = 0,
            rawCandidateCount = 3,
            validCandidateCount = 1,
            profileSkippedCandidateCount = 1,
            invalidCandidateCount = 1,
            matcherFailure = SiftRootSiftMatchFailure.NO_RATIO_MATCHES,
            weakGatePassed = false,
            strongGatePassed = false,
        ).safeSummary()

        assertTrue(summary.contains("raw=3"))
        assertTrue(summary.contains("valid=1"))
        assertTrue(summary.contains("profileSkipped=1"))
        assertTrue(summary.contains("invalid=1"))
        assertTrue(summary.contains("matcherFailure=NO_RATIO_MATCHES"))
        assertTrue(summary.contains("weakGate=false"))
        assertTrue(summary.contains("strongGate=false"))
        listOf("bitmap", "descriptor", "coordinate", "handle", "capsule", "chooser").forEach {
            assertFalse(summary.contains(it, ignoreCase = true), "telemetry leaked $it")
        }
    }

    @Test
    fun debugSummaryBlacklistsSensitiveTransportAndPayloadCategories() {
        val summary = MatchDiagnosticEvent(
            phase = MatchDiagnosticPhase.CANDIDATE_EVALUATED,
            candidateCount = 1,
            score = 0.5,
            margin = 0.1,
            ratioMutualMatches = 2,
            ransacInliers = 1,
            coverage = 0.2,
        ).safeSummary()

        listOf(
            "token",
            "access-token",
            "authorization",
            "key",
            "private-key",
            "descriptor",
            "descriptors",
            "image",
            "bitmap",
            "jpeg",
            "png",
            "capsuleId",
            "candidateId",
            "userId",
            "blobId",
            "identifier",
            "uuid",
            "request_id",
        ).forEach { forbidden ->
            assertFalse(summary.contains(forbidden, ignoreCase = true), "telemetry leaked $forbidden")
        }
    }
}
