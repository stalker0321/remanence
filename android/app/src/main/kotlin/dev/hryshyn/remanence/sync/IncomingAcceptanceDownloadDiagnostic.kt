package dev.hryshyn.remanence.sync

import dev.hryshyn.remanence.core.data.network.RecipientBlobDownloadFailure
import dev.hryshyn.remanence.core.data.network.RecipientBlobDownloadHeaderChecks

/** Safe local-path reason used only for bounded debug diagnostics. */
enum class IncomingAcceptanceLocalPathFailureReason {
    DESTINATION_NOT_FRESH,
    LOCAL_STORAGE,
    UNSAFE_TEMP_PATH,
    INVALID_RECOVERY_TEMP,
    RETURNED_PATH,
    RETURNED_SIZE,
    SYMLINK_PATH,
}

/**
 * Redacted evidence for one incoming recognition download boundary. The
 * values are enums, bounded HTTP status, and booleans only.
 */
data class IncomingAcceptanceDownloadDiagnostic(
    val category: Category,
    val transportReason: RecipientBlobDownloadFailure? = null,
    val localPathReason: IncomingAcceptanceLocalPathFailureReason? = null,
    val httpStatus: Int? = null,
    val retryable: Boolean,
    val headerChecks: RecipientBlobDownloadHeaderChecks? = null,
    val returnedPathMatches: Boolean? = null,
    val returnedSizeMatches: Boolean? = null,
    val noSymlinkPath: Boolean? = null,
) {
    init {
        require(httpStatus == null || httpStatus in 100..599) {
            "HTTP status must be a valid bounded status"
        }
        require((transportReason == null) != (localPathReason == null)) {
            "exactly one diagnostic reason is required"
        }
    }

    /** Stable, privacy-safe representation for DEBUG UI and Logcat. */
    fun safeSummary(): String = buildString {
        append("acceptance download category=").append(category.name)
        append(" reason=").append(transportReason?.name ?: localPathReason?.name)
        append(" status=").append(httpStatus?.toString() ?: "n/a")
        append(" retryable=").append(retryable)
        headerChecks?.let { checks ->
            append(" headers=")
            append("contentType=").append(checks.contentTypeExact)
            append(",contentLength=").append(checks.contentLengthExact)
            append(",etag=").append(checks.etagExact)
            append(",contentEncodingAbsent=").append(checks.contentEncodingAbsent)
            append(",transferEncodingAbsent=").append(checks.transferEncodingAbsent)
            append(",contentRangeAbsent=").append(checks.contentRangeAbsent)
            append(",trailerAbsent=").append(checks.trailerAbsent)
        }
        returnedPathMatches?.let { append(" returnedPath=").append(it) }
        returnedSizeMatches?.let { append(" returnedSize=").append(it) }
        noSymlinkPath?.let { append(" noSymlink=").append(it) }
    }

    enum class Category {
        DOWNLOAD_FAILURE,
        LOCAL_PATH_FAILURE,
    }

    companion object {
        fun fromFailure(
            failure: dev.hryshyn.remanence.core.data.network.RecipientBlobDownloadResult.Failure,
        ): IncomingAcceptanceDownloadDiagnostic {
            val localPathReason = when (failure.reason) {
                RecipientBlobDownloadFailure.DESTINATION_NOT_FRESH ->
                    IncomingAcceptanceLocalPathFailureReason.DESTINATION_NOT_FRESH
                RecipientBlobDownloadFailure.LOCAL_STORAGE ->
                    IncomingAcceptanceLocalPathFailureReason.LOCAL_STORAGE
                else -> null
            }
            return IncomingAcceptanceDownloadDiagnostic(
                category = if (localPathReason == null) {
                    Category.DOWNLOAD_FAILURE
                } else {
                    Category.LOCAL_PATH_FAILURE
                },
                transportReason = if (localPathReason == null) failure.reason else null,
                localPathReason = localPathReason,
                httpStatus = failure.httpStatus,
                retryable = failure.retryable,
                headerChecks = failure.headerChecks,
            )
        }

        fun localPathFailure(
            reason: IncomingAcceptanceLocalPathFailureReason,
            returnedPathMatches: Boolean? = null,
            returnedSizeMatches: Boolean? = null,
            noSymlinkPath: Boolean? = null,
            retryable: Boolean,
        ): IncomingAcceptanceDownloadDiagnostic = IncomingAcceptanceDownloadDiagnostic(
            category = Category.LOCAL_PATH_FAILURE,
            localPathReason = reason,
            retryable = retryable,
            returnedPathMatches = returnedPathMatches,
            returnedSizeMatches = returnedSizeMatches,
            noSymlinkPath = noSymlinkPath,
        )
    }
}
