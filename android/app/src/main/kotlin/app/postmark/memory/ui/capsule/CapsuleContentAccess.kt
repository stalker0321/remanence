package app.postmark.memory.ui.capsule

/**
 * FIX-REVIEW2-03: the UI-facing surface of on-demand capsule decryption.
 * Production routes may only reach [CapsuleContentSource] through
 * [GrantGuardedCapsuleContentSource], which revalidates the live memory-only
 * scan grant before every operation.
 */
interface CapsuleContentReader {
    suspend fun photoCount(capsuleId: String): Int

    suspend fun loadPhoto(capsuleId: String, ordinal: Int): DecryptedPhoto

    suspend fun noteText(capsuleId: String): String?
}

/**
 * FIX-REVIEW2-03: every on-demand decrypt/page load revalidates the SAME
 * grant through THE authoritative manager first. An expired, consumed, or
 * wrong grant decrypts nothing - the validator's failure propagates instead
 * of any plaintext byte.
 */
class GrantGuardedCapsuleContentSource(
    private val delegate: CapsuleContentReader,
    private val validateLiveGrant: () -> Unit,
) : CapsuleContentReader {

    override suspend fun photoCount(capsuleId: String): Int {
        validateLiveGrant()
        return delegate.photoCount(capsuleId)
    }

    override suspend fun loadPhoto(capsuleId: String, ordinal: Int): DecryptedPhoto {
        validateLiveGrant()
        return delegate.loadPhoto(capsuleId, ordinal)
    }

    override suspend fun noteText(capsuleId: String): String? {
        validateLiveGrant()
        return delegate.noteText(capsuleId)
    }
}
