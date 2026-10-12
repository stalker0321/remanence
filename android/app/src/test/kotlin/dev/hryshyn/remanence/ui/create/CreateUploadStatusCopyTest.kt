package dev.hryshyn.remanence.ui.create

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import dev.hryshyn.remanence.core.data.network.CapsuleRevokeFailure
import dev.hryshyn.remanence.R

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
            assertEquals(R.string.create_audit_upload_retry, retryable)
            assertEquals(R.string.create_audit_upload_terminal, terminal)
        }
        assertEquals(
            R.string.create_audit_upload_retry,
            createUploadPendingCopy(CreateViewModel.CreateUploadStatus.RetryableFailure(null)),
        )
        assertEquals(
            R.string.create_audit_upload_terminal,
            createUploadPendingCopy(CreateViewModel.CreateUploadStatus.TerminalFailure(null)),
        )
    }

    @Test
    fun revokeCopyNamesTheLimitWithoutClaimingRecipientDeletion() {
        val success = createRevokeCopy(
            CreateViewModel.CapsuleRevokeStatus.Failed(CapsuleRevokeFailure.NETWORK, true),
        )
        assertEquals(R.string.create_audit_revoke_network, success)

        val terminal = createRevokeCopy(
            CreateViewModel.CapsuleRevokeStatus.Failed(CapsuleRevokeFailure.WINDOW_EXPIRED, false),
        )
        assertEquals(R.string.create_audit_revoke_expired, terminal)
    }

    @Test
    fun previewReasonsSelectUsefulFixedCopyRatherThanInternalText() {
        assertEquals(R.string.create_audit_photos_count, createPreviewNotice(GeneratorPreviewState.Rejected(listOf("select 3..5 photos"))))
        assertEquals(R.string.create_audit_photos_unreadable, createPreviewNotice(GeneratorPreviewState.Unavailable("could not read a selected photo")))
        assertEquals(R.string.create_note_too_long, createPreviewNotice(GeneratorPreviewState.Rejected(listOf("the note exceeds its byte limit"))))
        assertEquals(R.string.create_audit_preview_unavailable, createPreviewNotice(GeneratorPreviewState.Unavailable("private-hostname")))
    }
}
