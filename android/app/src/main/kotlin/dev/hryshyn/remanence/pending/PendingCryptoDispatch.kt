package dev.hryshyn.remanence.pending

/**
 * T186: typed v2 crypto dispatch seam.
 * Sources: T184 contract + T185 `crypto-contract.md` §1–§2 (frozen:
 * `delivery_v2` messages, canonical domains, wrong-domain rejection).
 *
 * Both v1 and v2 opens go through genuine acceptance — verifier checks are
 * never disabled and mock claims are never labelled real encrypted opens.
 * The exact v2 envelope bytes arrive with M2/M3 + `delivery-v2-*.json`
 * fixtures (byte-parity first); until then this seam stays fail-closed
 * ([V2ContractUnavailable]) instead of fabricating wire shapes.
 */
object PendingCryptoDispatch {

    enum class CapsuleVersion { V1, V2_PENDING }

    sealed interface Dispatch {
        /** Existing v1 pipeline; handled by the current crypto stack. */
        data object V1Passthrough : Dispatch

        /**
         * Pending v2 path. [opaqueEnvelopeId] is an opaque server handle
         * only — never plaintext, keys, destination, or OTP.
         */
        data class V2Pending(val opaqueEnvelopeId: String) : Dispatch

        /** Frozen T185 crypto contract not yet integrated. Fail closed. */
        data object V2ContractUnavailable : Dispatch
    }

    /** `v2ContractFrozen` flips only when T185 api/crypto contracts land. */
    fun dispatch(
        version: CapsuleVersion,
        opaqueEnvelopeId: String?,
        v2ContractFrozen: Boolean = false,
    ): Dispatch = when (version) {
        CapsuleVersion.V1 -> Dispatch.V1Passthrough
        CapsuleVersion.V2_PENDING -> if (v2ContractFrozen && !opaqueEnvelopeId.isNullOrBlank()) {
            Dispatch.V2Pending(opaqueEnvelopeId)
        } else {
            Dispatch.V2ContractUnavailable
        }
    }
}
