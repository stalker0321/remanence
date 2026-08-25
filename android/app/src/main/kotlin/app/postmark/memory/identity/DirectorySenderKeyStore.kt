package app.postmark.memory.identity

import postmark.core.data.network.KeyBundleByIdResult
import postmark.core.model.KeyBundleId
import postmark.core.model.UserId

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
                ?: return SenderKeyResolution.Untrusted("own signing export unreadable")
            return SenderKeyResolution.Trusted(handle)
        }

        return when (val result = directoryFetch(senderKeyBundleId.value.toString())) {
            null -> SenderKeyResolution.Untrusted("directory unavailable")
            KeyBundleByIdResult.NotFound -> SenderKeyResolution.Untrusted("bundle unknown to the directory")
            is KeyBundleByIdResult.Failure -> SenderKeyResolution.Untrusted("directory lookup failed")
            is KeyBundleByIdResult.Found -> when {
                result.bundle.ownerUserId != senderUserId ->
                    SenderKeyResolution.Untrusted("bundle owner does not match the claimed sender")

                result.bundle.status == "REVOKED" ->
                    SenderKeyResolution.Untrusted("sender bundle is revoked")

                else -> {
                    val handle = CapsuleRoutingPolicy.senderVerifyingKeysetOrNull(
                        result.bundle.signingPublicKeysetB64Url,
                    )
                        ?: return SenderKeyResolution.Untrusted("directory signing keyset malformed")
                    SenderKeyResolution.Trusted(handle)
                }
            }
        }
    }
}
