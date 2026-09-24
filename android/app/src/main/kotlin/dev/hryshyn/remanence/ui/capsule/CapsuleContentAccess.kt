package dev.hryshyn.remanence.ui.capsule

import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorExpression

/**
 * ADR-018 receiver presentation admission for one authenticated capsule:
 * - [LegacyV1] keeps the existing individual-photo path unchanged;
 * - [Ber1] renders the sealed exact BER1 expression;
 * - [Unsupported] renders NOTHING plus a typed notice (never a fallback
 *   photo layout over a v2-intended capsule).
 */
sealed interface CapsulePresentationAdmission {
    data object LegacyV1 : CapsulePresentationAdmission

    data class Ber1(
        val expression: GeneratorExpression.ResolvedExpression,
    ) : CapsulePresentationAdmission

    data class Unsupported(val reason: String) : CapsulePresentationAdmission
}

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

    /**
     * S2b-receiver: the sealed v2-only track snapshot, or null when the
     * manifest carries none.
     *
     * Default: a reader with no content-manifest view reports null, so
     * every existing reader/test is unchanged (mirrors the
     * [presentationAdmission] default below).
     */
    suspend fun trackSnapshot(capsuleId: String): CapsuleTrackSnapshotV1? = null

    /**
     * Default: a reader that has no content-manifest view is treated as the
     * legacy v1 photo path, so every existing reader/test is unchanged.
     */
    suspend fun presentationAdmission(capsuleId: String): CapsulePresentationAdmission =
        CapsulePresentationAdmission.LegacyV1
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

    override suspend fun trackSnapshot(capsuleId: String): CapsuleTrackSnapshotV1? {
        validateLiveGrant()
        // Immutable validated data like the note: refused on a dead grant,
        // no scrub needed (no mutable plaintext bytes).
        val snapshot = delegate.trackSnapshot(capsuleId)
        validateLiveGrant()
        return snapshot
    }

    override suspend fun presentationAdmission(capsuleId: String): CapsulePresentationAdmission {
        validateLiveGrant()
        val admission = delegate.presentationAdmission(capsuleId)
        validateLiveGrant()
        return admission
    }
}
