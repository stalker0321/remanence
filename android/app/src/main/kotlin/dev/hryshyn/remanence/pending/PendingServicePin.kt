package dev.hryshyn.remanence.pending

import dev.hryshyn.remanence.BuildConfig

/**
 * T186 M12b: selected service verifier + deployment UUID for the pending
 * sender AND the claim acceptance (same pin feeds both — never an
 * R-carried key on either path).
 *
 * Out-of-band root-selected configuration (per-epoch PUBLIC pair via
 * `-Premanence.pending.serviceKeyId` / `-Premanence.pending.serviceKeysetB64`,
 * see `tasks/T185/t186-dev-service-pin.md` for the T185 handshake): the
 * 099 dev-fixture pair whose private half was discarded is TEST-ONLY
 * (unit fixtures) and is NEVER a runtime fallback — no authentic signer
 * can be derived from it. Production/beta builds hard-wire empty
 * BuildConfig values, so selection is null there (fail closed).
 */
data class PendingServicePin(
    val deploymentUuid: String,
    /** Tink binary public keyset, standard base64 (Ed25519, 69-byte framing). */
    val publicKeysetB64: String,
)

object PendingServicePinConfig {

    /**
     * The root-selected pin for pending debug builds, else null (fail
     * closed). Absent/blank/malformed UUID, undecodable keyset, or a
     * keyset that is not a usable Ed25519 verification key ⇒ null;
     * production and beta are always null (empty BuildConfig + gate).
     */
    fun selected(): PendingServicePin? {
        if (!PendingFeatureGate.isEnabled()) return null
        return select(
            BuildConfig.REMANENCE_PENDING_SERVICE_KEY_ID,
            BuildConfig.REMANENCE_PENDING_SERVICE_KEYSET_B64,
        )
    }

    /**
     * Pure validation half (unit-tested without BuildConfig): strict
     * canonical UUID (lowercase form, no urn/braces/uppercase aliases)
     * plus a real keyset parse that proves an Ed25519 `PublicKeyVerify`
     * primitive is actually available — junk bytes, wrong-algorithm
     * keysets and private keysets all fail closed. A pin that cannot
     * authenticate a signer must never reach publication.
     */
    fun select(deploymentUuid: String, publicKeysetB64: String): PendingServicePin? {
        val trimmedUuid = deploymentUuid.trim()
        val uuid = try {
            java.util.UUID.fromString(trimmedUuid)
        } catch (_: IllegalArgumentException) {
            return null
        }
        // Strict canonical form: fromString accepts urn/braces/uppercase
        // aliases that must never select a deployment pin.
        if (uuid.toString() != trimmedUuid) return null
        val trimmedKeyset = publicKeysetB64.trim()
        if (parseVerificationKeyset(trimmedKeyset) == null) return null
        return PendingServicePin(trimmedUuid, trimmedKeyset)
    }

    /**
     * Usability gate for an already-formed pin (the sender checks this
     * instead of blankness, so a malformed configured pin BLOCKS
     * publication rather than minting S under it).
     */
    fun usable(pin: PendingServicePin?): Boolean {
        if (pin == null) return false
        return select(pin.deploymentUuid, pin.publicKeysetB64) != null
    }

    /**
     * Parses WITHOUT secrets and proves Ed25519 verification: null for
     * undecodable bytes, non-keyset junk, private keysets (secret
     * material refused), wrong-algorithm keysets and empty keysets. No
     * signing is performed and no private material is touched. Checks
     * are deterministic proto inspections plus the real Tink parse (no
     * reliance on any single library-version behavior).
     */
    fun parseVerificationKeyset(publicKeysetB64: String): com.google.crypto.tink.KeysetHandle? {
        val keysetBytes = try {
            java.util.Base64.getDecoder().decode(publicKeysetB64.trim())
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (keysetBytes.isEmpty()) return null
        return try {
            // Deterministic shape gate first: every key must be a public
            // Ed25519 key (rejects private material and foreign
            // algorithms before Tink even parses).
            val proto = com.google.crypto.tink.proto.Keyset.parseFrom(keysetBytes)
            if (proto.keyCount < 1) return null
            for (key in proto.keyList) {
                if (key.keyData.keyMaterialType !=
                    com.google.crypto.tink.proto.KeyMaterialType.ASYMMETRIC_PUBLIC
                ) {
                    return null
                }
                if (key.keyData.typeUrl != ED25519_PUBLIC_KEY_TYPE_URL) return null
            }
            val handle = com.google.crypto.tink.TinkProtoKeysetFormat
                .parseKeysetWithoutSecret(keysetBytes)
            handle.getPrimitive(com.google.crypto.tink.PublicKeyVerify::class.java)
            handle
        } catch (_: Exception) {
            null
        }
    }

    private const val ED25519_PUBLIC_KEY_TYPE_URL: String =
        "type.googleapis.com/google.crypto.tink.Ed25519PublicKey"
}
