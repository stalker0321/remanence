package dev.hryshyn.remanence.ui.capsule

/**
 * FIX-REVIEW2-03: the UI-facing surface of on-demand capsule decryption.
 * Production routes may only reach [CapsuleContentSource] through
 * [GrantGuardedCapsuleContentSource], which revalidates the live memory-only
 * scan grant around every operation.
 */
interface CapsuleContentReader {
    suspend fun photoCount(capsuleId: String): Int

    suspend fun loadPhoto(capsuleId: String, ordinal: Int): DecryptedPhoto

    suspend fun noteText(capsuleId: String): String?
}

/** Opaque route-local binding; navigation itself carries only a grant ID. */
internal class CapsuleContentBinding(
    val capsuleId: String,
    val reader: CapsuleContentReader,
) {
    override fun toString(): String = "CapsuleContentBinding(<redacted>)"
}

/**
 * FIX-REVIEW2-03 / FIX-REVIEW3-02: every on-demand decrypt/page load
 * revalidates the SAME grant through THE authoritative manager both BEFORE
 * and AFTER the suspended operation - one validator, no second source of
 * truth. An expired, consumed, or wrong grant decrypts nothing and never
 * yields plaintext: a photo that finished decrypting into a dead grant has
 * its bytes zeroed before the refusal propagates. (A note is an immutable
 * String and cannot be scrubbed; it is simply refused.)
 */
class GrantGuardedCapsuleContentSource(
    private val delegate: CapsuleContentReader,
    private val validateLiveGrant: () -> Unit,
) : CapsuleContentReader {

    override suspend fun photoCount(capsuleId: String): Int {
        validateLiveGrant()
        val count = delegate.photoCount(capsuleId)
        validateLiveGrant()
        return count
    }

    override suspend fun loadPhoto(capsuleId: String, ordinal: Int): DecryptedPhoto {
        validateLiveGrant()
        val photo = delegate.loadPhoto(capsuleId, ordinal)
        try {
            // The grant must still be alive now that plaintext exists.
            validateLiveGrant()
        } catch (refused: Exception) {
            photo.jpegBytes.fill(0)
            throw refused
        }
        return photo
    }

    override suspend fun noteText(capsuleId: String): String? {
        validateLiveGrant()
        val note = delegate.noteText(capsuleId)
        validateLiveGrant()
        return note
    }
}
