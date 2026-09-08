package dev.hryshyn.remanence.sync

import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.core.data.network.RecipientBlobDownloadFailure
import dev.hryshyn.remanence.core.data.network.RecipientBlobDownloadHeaderChecks
import dev.hryshyn.remanence.core.data.network.RecipientBlobDownloadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingAcceptanceDownloadDiagnosticTest {

    @Test
    fun invalidResponseIncludesBoundedHeaderEvidence() {
        val diagnostic = IncomingAcceptanceDownloadDiagnostic.fromFailure(
            RecipientBlobDownloadResult.Failure(
                reason = RecipientBlobDownloadFailure.INVALID_RESPONSE,
                httpStatus = 200,
                retryable = false,
                headerChecks = RecipientBlobDownloadHeaderChecks(
                    contentTypeExact = false,
                    contentLengthExact = true,
                    etagExact = false,
                    contentEncodingAbsent = true,
                    transferEncodingAbsent = true,
                    contentRangeAbsent = true,
                    trailerAbsent = true,
                ),
            ),
        )

        val summary = diagnostic.safeSummary()
        assertTrue(summary.contains("category=DOWNLOAD_FAILURE"))
        assertTrue(summary.contains("reason=INVALID_RESPONSE"))
        assertTrue(summary.contains("status=200"))
        assertTrue(summary.contains("retryable=false"))
        assertTrue(summary.contains("contentType=false"))
        assertTrue(summary.contains("etag=false"))
        assertFalse(summary.contains("application/octet-stream"))
        assertFalse(summary.contains("Bearer"))
    }

    @Test
    fun integrityFailureHasNoDigestOrPayloadDetail() {
        val summary = IncomingAcceptanceDownloadDiagnostic.fromFailure(
            RecipientBlobDownloadResult.Failure(
                reason = RecipientBlobDownloadFailure.INTEGRITY_FAILED,
                httpStatus = 200,
                retryable = false,
            ),
        ).safeSummary()

        assertTrue(summary.contains("reason=INTEGRITY_FAILED"))
        assertTrue(summary.contains("status=200"))
        assertFalse(summary.contains("0123456789abcdef"))
        assertFalse(summary.contains("sha256"))
        assertFalse(summary.contains("payload"))
        assertFalse(summary.contains("ciphertext"))
    }

    @Test
    fun canonicalTransportReasonsAndRetryabilityArePreserved() {
        val cases = listOf(
            Triple(RecipientBlobDownloadFailure.AUTH_INVALID, 401, false),
            Triple(RecipientBlobDownloadFailure.NOT_FOUND, 404, false),
            Triple(RecipientBlobDownloadFailure.RATE_LIMITED, 429, true),
            Triple(RecipientBlobDownloadFailure.INTERNAL_ERROR, 503, true),
            Triple(RecipientBlobDownloadFailure.HTTP, 500, false),
            Triple(RecipientBlobDownloadFailure.NETWORK, null, true),
        )

        cases.forEach { (reason, status, retryable) ->
            val summary = IncomingAcceptanceDownloadDiagnostic.fromFailure(
                RecipientBlobDownloadResult.Failure(
                    reason = reason,
                    httpStatus = status,
                    retryable = retryable,
                ),
            ).safeSummary()
            assertTrue(summary, summary.contains("reason=${reason.name}"))
            assertTrue(summary, summary.contains("status=${status ?: "n/a"}"))
            assertTrue(summary, summary.contains("retryable=$retryable"))
        }
    }

    @Test
    fun destinationAndReturnedPathFailuresUseLocalPathCategory() {
        val destination = IncomingAcceptanceDownloadDiagnostic.fromFailure(
            RecipientBlobDownloadResult.Failure(
                reason = RecipientBlobDownloadFailure.DESTINATION_NOT_FRESH,
                httpStatus = 200,
                retryable = false,
            ),
        )
        assertEquals(IncomingAcceptanceDownloadDiagnostic.Category.LOCAL_PATH_FAILURE, destination.category)
        assertEquals(IncomingAcceptanceLocalPathFailureReason.DESTINATION_NOT_FRESH, destination.localPathReason)

        val returnedPath = IncomingAcceptanceDownloadDiagnostic.localPathFailure(
            reason = IncomingAcceptanceLocalPathFailureReason.RETURNED_PATH,
            returnedPathMatches = false,
            returnedSizeMatches = true,
            noSymlinkPath = true,
            retryable = false,
        )
        assertEquals(IncomingAcceptanceLocalPathFailureReason.RETURNED_PATH, returnedPath.localPathReason)
        assertTrue(returnedPath.safeSummary().contains("returnedPath=false"))
        val returnedPathSummary = returnedPath.safeSummary()
        assertFalse(returnedPathSummary, returnedPathSummary.contains("/home/"))
    }

    @Test
    fun diagnosticStateIsDetailedOnlyForDebugBuilds() {
        val diagnostic = IncomingAcceptanceDownloadDiagnostic.fromFailure(
            RecipientBlobDownloadResult.Failure(
                reason = RecipientBlobDownloadFailure.INVALID_RESPONSE,
                httpStatus = 200,
                retryable = false,
            ),
        )

        IncomingAcceptanceDiagnostics.report(diagnostic)
        if (BuildConfig.DEBUG) {
            assertEquals(diagnostic.safeSummary(), IncomingAcceptanceDiagnostics.state.value)
        } else {
            assertEquals("acceptance download failure", IncomingAcceptanceDiagnostics.state.value)
        }
    }

    @Test
    fun summaryHasNoSensitiveCategoryValues() {
        val summary = IncomingAcceptanceDownloadDiagnostic.fromFailure(
            RecipientBlobDownloadResult.Failure(
                reason = RecipientBlobDownloadFailure.INVALID_RESPONSE,
                httpStatus = 502,
                retryable = true,
            ),
        ).safeSummary()
        val forbiddenValues = listOf(
            "Authorization",
            "Bearer",
            "https://",
            "request_id",
            "capsule-id-secret",
            "descriptor-bytes",
            "image-bytes",
            "access-token-secret",
            "user@example.com",
            "sha256-secret",
            "private/path",
        )
        forbiddenValues.forEach { forbidden ->
            assertFalse("diagnostic leaked $forbidden", summary.contains(forbidden, ignoreCase = true))
        }
    }
}
