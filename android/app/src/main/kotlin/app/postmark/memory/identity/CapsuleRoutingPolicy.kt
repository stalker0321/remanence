package app.postmark.memory.identity

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.subtle.Base64
import java.util.UUID
import postmark.core.data.db.OutboxCapsuleEntity
import postmark.core.model.KeyBundleId
import postmark.core.model.UserId

/**
 * FIX-REVIEW2-01: strict outcome of parsing one persisted outbox row's
 * routing identities. There is no lenient mode: a row is either fully
 * well-formed, an explicitly legacy (v2-migrated) self-send row whose sender
 * columns are genuinely NULL, or CORRUPT - and corruption always fails
 * closed instead of being repaired into a fake self-send.
 */
sealed interface CapsuleRoutingResolution {

    /** Fully valid identities; [senderSigningPublicKeysetB64Url] may be null on legacy rows. */
    data class Resolved(
        val senderUserId: UserId,
        val recipientUserId: UserId,
        val senderKeyBundleId: KeyBundleId,
        val recipientKeyBundleId: KeyBundleId,
        /**
         * Row-carried sender PUBLIC keyset export when present. It is a
         * transport/cache candidate only (FIX-REVIEW2-04): trust never comes
         * from storage adjacency alone.
         */
        val senderSigningPublicKeysetB64Url: String?,
    ) : CapsuleRoutingResolution

    /** Malformed non-null persisted material; consumers must refuse the capsule. */
    data class Corrupt(val field: String) : CapsuleRoutingResolution
}

/**
 * FIX-REVIEW2-01: THE one strict parser of capsule routing identity material,
 * shared by Scan verification and capsule content decryption so their policies
 * can never diverge.
 *
 * Policy (threat model docs/security.md section 1: tampering must fail closed):
 * - `recipient_user_id` / `recipient_key_bundle_id` are mandatory and must be
 *   valid typed UUIDs - malformed values NEVER fall back to the authenticated
 *   account.
 * - A non-null `sender_user_id` / `sender_key_bundle_id` that is not a valid
 *   typed UUID fails closed; only genuinely NULL columns resolve through the
 *   documented legacy v2 self-send fallback (sender VALUES = recipient
 *   VALUES), because v2 rows were same-account by construction.
 * - A non-null `sender_signing_public_keyset_b64` must decode to a valid,
 *   non-secret Ed25519 public keyset; anything else fails closed and NEVER
 *   falls back to the own signing export after an error.
 */
object CapsuleRoutingPolicy {

    private const val ED25519_PUBLIC_TYPE_URL =
        "type.googleapis.com/google.crypto.tink.Ed25519PublicKey"

    fun resolve(row: OutboxCapsuleEntity): CapsuleRoutingResolution {
        // Recipient routing is mandatory infrastructure data: malformed is
        // always corrupt, never "treat as our own".
        val recipientUser = typedUuid(row.recipientUserId)?.let(::UserId)
            ?: return CapsuleRoutingResolution.Corrupt("recipient_user_id")
        val recipientBundle = typedUuid(row.recipientKeyBundleId)?.let(::KeyBundleId)
            ?: return CapsuleRoutingResolution.Corrupt("recipient_key_bundle_id")

        val senderUser = row.senderUserId?.let { raw ->
            typedUuid(raw)?.let(::UserId)
                ?: return CapsuleRoutingResolution.Corrupt("sender_user_id")
        } ?: recipientUser

        val senderBundle = row.senderKeyBundleId?.let { raw ->
            typedUuid(raw)?.let(::KeyBundleId)
                ?: return CapsuleRoutingResolution.Corrupt("sender_key_bundle_id")
        } ?: recipientBundle

        row.senderSigningPublicKeysetB64?.let { encoded ->
            if (senderVerifyingKeysetOrNull(encoded) == null) {
                return CapsuleRoutingResolution.Corrupt("sender_signing_public_keyset_b64")
            }
        }

        return CapsuleRoutingResolution.Resolved(
            senderUserId = senderUser,
            recipientUserId = recipientUser,
            senderKeyBundleId = senderBundle,
            recipientKeyBundleId = recipientBundle,
            senderSigningPublicKeysetB64Url = row.senderSigningPublicKeysetB64,
        )
    }

    /**
     * Strictly parses one base64url public keyset into a verification handle.
     * Accepts ONLY non-secret Ed25519 public keysets; any other algorithm,
     * secret-bearing keyset, or malformed encoding returns null.
     */
    fun senderVerifyingKeysetOrNull(encoded: String): KeysetHandle? =
        runCatching {
            val keyset = TinkProtoKeysetFormat.parseKeysetWithoutSecret(
                Base64.urlSafeDecode(encoded),
            )
            val keyInfos = keyset.keysetInfo.keyInfoList
            require(keyInfos.isNotEmpty()) { "empty keyset" }
            require(keyInfos.all { it.typeUrl == ED25519_PUBLIC_TYPE_URL }) {
                "non-Ed25519 key material"
            }
            keyset
        }.getOrNull()

    /** True when both strings are exactly valid canonical typed UUIDs. */
    private fun typedUuid(raw: String): UUID? =
        runCatching { UUID.fromString(raw) }.getOrNull()
}
