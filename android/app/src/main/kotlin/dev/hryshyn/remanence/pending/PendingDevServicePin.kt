package dev.hryshyn.remanence.pending

/**
 * T186 M12: selected service verifier + deployment UUID for the pending
 * sender (T185 `t186-dev-service-pin.md` out-of-band bootstrap).
 *
 * Production default is null/fail-closed: without a deployment-chosen
 * pin history the sender cannot mint a valid `claim_signing_key_id`
 * (never invented, never taken from R-carried keys) and PENDING_V2
 * publication stops BLOCKED instead of fabricating trust.
 */
data class PendingServicePin(
    val deploymentUuid: String,
    /** Tink binary public keyset, standard base64 (Ed25519, 69-byte framing). */
    val publicKeysetB64: String,
)

/**
 * TEST-ONLY synthetic dev pin (T185 out-of-band bootstrap). PUBLIC
 * keyset only — the private half is stored nowhere in this repo
 * (root-owned integration scope if a shared synthetic deployment ever
 * stands up). Returned ONLY for pending-enabled builds; every other
 * build gets null (production never sees the dev pair).
 */
object PendingDevServicePin {

    const val DEPLOYMENT_UUID: String = "a3618c4c-fc28-4847-9f4c-0681e9b531bc"

    const val PUBLIC_KEYSET_B64: String =
        "CJ+h4NwGEmsKXwo3dHlwZS5nb29nbGVhcGlzLmNvbS9nb29nbGUuY3J5cHRvLnRpbmsuRWQyNTUxOVB1YmxpY0tleRIiEiA3kFtWMr1rV+z+3f1amnvWH+fVs4KGPkrm+bloQaEyVhgDEAEYn6Hg3AYgAQ=="

    val pin: PendingServicePin = PendingServicePin(DEPLOYMENT_UUID, PUBLIC_KEYSET_B64)

    /** The out-of-band dev pin for pending builds, else null (fail closed). */
    fun pinIfEnabled(): PendingServicePin? =
        if (PendingFeatureGate.isEnabled()) pin else null
}
