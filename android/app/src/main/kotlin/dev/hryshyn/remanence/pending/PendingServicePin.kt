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
     * closed). Absent/blank/malformed UUID or undecodable keyset ⇒ null;
     * production and beta are always null (empty BuildConfig + gate).
     */
    fun selected(): PendingServicePin? {
        if (!PendingFeatureGate.isEnabled()) return null
        return select(
            BuildConfig.REMANENCE_PENDING_SERVICE_KEY_ID,
            BuildConfig.REMANENCE_PENDING_SERVICE_KEYSET_B64,
        )
    }

    /** Pure validation half (unit-tested without BuildConfig). */
    fun select(deploymentUuid: String, publicKeysetB64: String): PendingServicePin? {
        val uuid = try {
            java.util.UUID.fromString(deploymentUuid.trim())
            deploymentUuid.trim()
        } catch (_: IllegalArgumentException) {
            return null
        }
        val keyset = try {
            java.util.Base64.getDecoder().decode(publicKeysetB64.trim())
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (keyset.isEmpty()) return null
        return PendingServicePin(uuid, publicKeysetB64.trim())
    }
}
