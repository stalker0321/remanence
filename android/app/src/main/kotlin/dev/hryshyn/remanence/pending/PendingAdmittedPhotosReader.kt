package dev.hryshyn.remanence.pending

import dev.hryshyn.remanence.core.crypto.ExpressionReceiverAdmission
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.ui.capsule.CapsuleContentReader
import dev.hryshyn.remanence.ui.capsule.CapsulePresentationAdmission
import dev.hryshyn.remanence.ui.capsule.DecryptedPhoto
import dev.hryshyn.remanence.ui.collage.BundledCollageRenderCapability

/**
 * T186 M14d-scan: [CapsuleContentReader] over an already-opened
 * pending gift ([PendingAdmittedContent.OpenedPhotos], decrypted under
 * the live grant at presentation time). The route wraps it in the
 * grant guard like every other source; the caller that opened the
 * photos owns wiping them on route close/failure.
 *
 * Admission carries the ACTUAL validated content manifest (same
 * mapping as the v1 incoming source, same renderers — no restyle):
 * manifest version 1 with a pager-compatible photo count presents as
 * [LegacyV1]; version 2 runs the shared expression admission
 * ([Ber1] when supported); anything else is an explicit [Unsupported]
 * typed notice that enqueues no retirement. A photo-count fallback
 * over a v2-intended gift never happens.
 */
internal class PendingAdmittedPhotosReader(
    private val capsuleId: String,
    private val opened: PendingAdmittedContent.OpenedPhotos,
) : CapsuleContentReader {

    private fun requireId(capsuleId: String) {
        if (capsuleId.isBlank() || capsuleId != this.capsuleId) {
            throw IllegalStateException("pending capsule binding mismatch")
        }
    }

    override suspend fun photoCount(capsuleId: String): Int {
        requireId(capsuleId)
        return opened.photosByOrdinal.size
    }

    override suspend fun loadPhoto(capsuleId: String, ordinal: Int): DecryptedPhoto {
        requireId(capsuleId)
        val bytes = opened.photosByOrdinal[ordinal]
            ?: throw IllegalStateException("pending photo ordinal unknown")
        return DecryptedPhoto(ordinal, bytes)
    }

    override suspend fun noteText(capsuleId: String): String? {
        requireId(capsuleId)
        return opened.note
    }

    override suspend fun trackSnapshot(capsuleId: String): CapsuleTrackSnapshotV1? {
        requireId(capsuleId)
        return opened.manifest.trackSnapshot
    }

    override suspend fun presentationAdmission(capsuleId: String): CapsulePresentationAdmission {
        requireId(capsuleId)
        val manifest = opened.manifest
        if (manifest.protocolVersion == 1) {
            // Legacy v1 frame (parse already rejected any v2-only
            // expression/track fields): the photo pager needs 3..5.
            return if (opened.photosByOrdinal.size in
                ProtocolV1Limits.PHOTO_COUNT_MIN..ProtocolV1Limits.PHOTO_COUNT_MAX
            ) {
                CapsulePresentationAdmission.LegacyV1
            } else {
                CapsulePresentationAdmission.Unsupported(
                    "pending gift carries ${opened.photosByOrdinal.size} photos; " +
                        "the photo pager needs 3..5",
                )
            }
        }
        // Version 2: the shared expression gate (same capability object
        // as the v1 incoming source) — supported layouts render through
        // the existing BER1/collage path, the rest is a typed notice.
        return when (
            val admitted = ExpressionReceiverAdmission.admit(
                manifest,
                BundledCollageRenderCapability,
            )
        ) {
            is ExpressionReceiverAdmission.Result.Supported ->
                CapsulePresentationAdmission.Ber1(admitted.expression)
            is ExpressionReceiverAdmission.Result.Unsupported ->
                CapsulePresentationAdmission.Unsupported(admitted.reason)
        }
    }
}
