package dev.hryshyn.remanence.identity

import dev.hryshyn.remanence.core.data.network.KeyBundleByIdResult
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.wiring.PreparedIdentity

/**
 * FIX-REVIEW2-04: production [TrustedSenderKeyStore] over the authenticated
 * key-directory endpoint (`GET /v1/directory/key-bundles/{id}`,
 * docs/protocol.md section 6). The injectable providers keep ONE policy
 * shared by production wiring and tests.
 *
 * Resolution policy:
 * - A claimed identity matching the authenticated local account AND its
 *   active bundle resolves through our own immutable public export without
 *   any network call - the only provable self-send shortcut (M1 legacy rows
 *   included, because their routing strictly parses to exactly these own
 *   values).
 * - Everything else resolves ONLY through the directory: the bundle must be
 *   found, OWNED by the claimed sender user, not REVOKED, and carry a
 *   well-formed non-secret Ed25519 signing keyset. Row-carried key exports
 *   are transport/cache candidates and never decide trust here; results are
 *   deliberately NOT cached so a later revocation cannot be outrun by a
 *   stale local copy.
 */
class DirectorySenderKeyStore(
    private val directoryFetch: suspend (keyBundleId: String) -> KeyBundleByIdResult?,
    private val ownAccount: suspend () -> OwnAccount?,
) : TrustedSenderKeyStore {

    /** The authenticated account's immutable self verification material. */
    data class OwnAccount(
        val userId: UserId,
        val activeKeyBundleId: KeyBundleId,
        /** Own PUBLIC Ed25519 keyset export (base64url); public material only. */
        val publicSigningExportB64Url: String,
    )

    override suspend fun senderVerifyingKeyset(
        senderUserId: UserId,
        senderKeyBundleId: KeyBundleId,
    ): SenderKeyResolution {
        val own = ownAccount()
        if (own != null && own.userId == senderUserId && own.activeKeyBundleId == senderKeyBundleId) {
            val handle = CapsuleRoutingPolicy.senderVerifyingKeysetOrNull(own.publicSigningExportB64Url)
                ?: return SenderKeyResolution.Untrusted(SenderKeyUntrustedReason.MALFORMED_KEY)
            return SenderKeyResolution.Trusted(handle)
        }

        return when (val result = directoryFetch(senderKeyBundleId.value.toString())) {
            null -> SenderKeyResolution.Unavailable(SenderKeyUnavailableReason.NO_SESSION_OR_SOURCE)
            KeyBundleByIdResult.NotFound ->
                SenderKeyResolution.Untrusted(SenderKeyUntrustedReason.UNKNOWN_BUNDLE)
            is KeyBundleByIdResult.Failure ->
                SenderKeyResolution.Unavailable(SenderKeyUnavailableReason.DIRECTORY_UNAVAILABLE)
            is KeyBundleByIdResult.Found -> when {
                result.bundle.keyBundleId != senderKeyBundleId ->
                    SenderKeyResolution.Untrusted(SenderKeyUntrustedReason.BUNDLE_ID_MISMATCH)

                result.bundle.protocolVersion != ProtocolV1Limits.PROTOCOL_VERSION ||
                    result.bundle.suite != PreparedIdentity.SUITE ->
                    SenderKeyResolution.Untrusted(
                        SenderKeyUntrustedReason.UNSUPPORTED_PROTOCOL_OR_SUITE,
                    )

                result.bundle.ownerUserId != senderUserId ->
                    SenderKeyResolution.Untrusted(SenderKeyUntrustedReason.OWNER_MISMATCH)

                result.bundle.status == "REVOKED" ->
                    SenderKeyResolution.Untrusted(SenderKeyUntrustedReason.REVOKED)

                else -> {
                    val handle = CapsuleRoutingPolicy.senderVerifyingKeysetOrNull(
                        result.bundle.signingPublicKeysetB64Url,
                    )
                        ?: return SenderKeyResolution.Untrusted(SenderKeyUntrustedReason.MALFORMED_KEY)
                    SenderKeyResolution.Trusted(handle)
                }
            }
        }
    }
}
