package dev.hryshyn.remanence.pending

import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.ui.capsule.CapsuleContentReader
import dev.hryshyn.remanence.ui.capsule.CapsulePresentationAdmission
import dev.hryshyn.remanence.ui.capsule.DecryptedPhoto

/**
 * T186 M14d-scan: [CapsuleContentReader] over an already-opened
 * pending gift ([PendingAdmittedContent.OpenedPhotos], decrypted under
 * the live grant at presentation time). The route wraps it in the
 * grant guard like every other source; the caller that opened the
 * photos owns wiping them on route close/failure.
 *
 * Admission mapping: the v1 photo pager requires
 * 3..5 photos — a pending gift inside that range presents as
 * [LegacyV1] (note included); anything else (1–2 or 6+ photos, which
 * v2 publications allow but the pager cannot lay out) is an explicit
 * [Unsupported] typed notice, never a fallback layout. Unsupported
 * presentation enqueues no retirement (nothing was presented).
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

    override suspend fun presentationAdmission(capsuleId: String): CapsulePresentationAdmission {
        requireId(capsuleId)
        return if (opened.photosByOrdinal.size in
            ProtocolV1Limits.PHOTO_COUNT_MIN..ProtocolV1Limits.PHOTO_COUNT_MAX
        ) {
            CapsulePresentationAdmission.LegacyV1
        } else {
            CapsulePresentationAdmission.Unsupported(
                "pending gift carries ${opened.photosByOrdinal.size} photos; the photo pager needs 3..5",
            )
        }
    }
}
