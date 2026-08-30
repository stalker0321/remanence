package dev.hryshyn.remanence.ui.create

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * User-facing Create send copy must stay fixed. Persisted last_error_code
 * values remain on [CreateViewModel.CreateUploadStatus] for diagnostics.
 */
class CreateUploadStatusCopyTest {

    @Test
    fun retryableAndTerminalCopyNeverIncludesPersistedErrorCodes() {
        val codes = listOf(
            "SIGNATURE_INVALID",
            "INTERNAL_ERROR",
            "BLOB_HASH_MISMATCH",
            "RECIPIENT_KEY_REVOKED",
        )
        for (code in codes) {
            val retryable = createUploadPendingCopy(
                CreateViewModel.CreateUploadStatus.RetryableFailure(code),
            )
            val terminal = createUploadPendingCopy(
                CreateViewModel.CreateUploadStatus.TerminalFailure(code),
            )
            assertEquals("Send needs a retry.", retryable)
            assertEquals("Send failed permanently.", terminal)
            assertFalse(retryable.contains(code))
            assertFalse(terminal.contains(code))
        }
        assertEquals(
            "Send needs a retry.",
            createUploadPendingCopy(CreateViewModel.CreateUploadStatus.RetryableFailure(null)),
        )
        assertEquals(
            "Send failed permanently.",
            createUploadPendingCopy(CreateViewModel.CreateUploadStatus.TerminalFailure(null)),
        )
    }
}
