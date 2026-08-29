package dev.hryshyn.remanence.identity

import com.google.crypto.tink.KeysetHandle
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.UserId

/**
 * FIX-REVIEW2-04: THE trust boundary for sender verification material.
 * The capsule acceptance gate receives its Ed25519 verifier ONLY through
 * this store, keyed by the immutable pair (sender_user_id,
 * sender_key_bundle_id) plus the directory lifecycle status - never from a
 * keyset merely because it is stored next to the capsule row (a storage
 * writer must not be able to substitute key + statement + signature as one
 * forged set; docs/security.md sections 2 and 6.4).
 *
 * A malicious LIVE directory substituting keys remains the documented
 * limitation (key transparency is out of MVP scope); this boundary only
 * guarantees that local database/object tampering cannot mint trust.
 */
interface TrustedSenderKeyStore {

    /**
     * Resolves the Ed25519 verification keyset for the claimed sender
     * identity, or refuses untrusted material.
     */
    suspend fun senderVerifyingKeyset(
        senderUserId: UserId,
        senderKeyBundleId: KeyBundleId,
    ): SenderKeyResolution
}

enum class SenderKeyUntrustedReason {
    MALFORMED_KEY,
    UNKNOWN_BUNDLE,
    OWNER_MISMATCH,
    REVOKED,
}

enum class SenderKeyUnavailableReason {
    NO_SESSION_OR_SOURCE,
    DIRECTORY_UNAVAILABLE,
}

/** Outcome of one trusted lookup; only [Trusted] supplies verification material. */
sealed interface SenderKeyResolution {

    /** Directory-proven (or provably own) Ed25519 public keyset. */
    data class Trusted(val verifyingKeyset: KeysetHandle) : SenderKeyResolution

    /** Terminal identity/key rejection; callers must not substitute another key. */
    data class Untrusted(val reason: SenderKeyUntrustedReason) : SenderKeyResolution

    /** Operationally unavailable source; callers may retry without trusting any key. */
    data class Unavailable(val reason: SenderKeyUnavailableReason) : SenderKeyResolution
}
