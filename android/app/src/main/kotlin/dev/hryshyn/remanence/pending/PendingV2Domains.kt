package dev.hryshyn.remanence.pending

/**
 * T186: frozen v2 canonical domains (T185 `crypto-contract.md` §2, M1).
 *
 * Every new signature domain is distinct and NUL-delimited:
 * `ASCII(domain) || 0x00 || canonical_message`. The delimiter intentionally
 * differs from the frozen v1 publish prefix; v1 bytes are untouched.
 * Covered by `PendingV2DomainsTest` byte-parity against the frozen M1
 * fixtures — never invented wire shapes.
 */
object PendingV2Domains {
    const val DELIVERY_ADDRESS = "postmark/delivery-address/v1"
    const val DELIVERY_KEY = "postmark/delivery-key/v1"
    const val ARTIFACT_V2 = "postmark/artifact/v2"
    const val PENDING_PUBLISH_V2 = "postmark/pending-publish/v2"
    const val CLAIM_BINDING_V1 = "postmark/claim-binding/v1"
    const val CLAIMED_ENVELOPE_V2 = "postmark/claimed-envelope/v2"
    const val CLAIM_CHALLENGE_V1 = "postmark/claim-challenge/v1"
    const val CLAIM_INTENT_V2 = "postmark/claim-intent/v2"

    /** Exact domain framing: ASCII bytes + NUL + canonical message bytes. */
    fun frame(domain: String, canonicalMessage: ByteArray): ByteArray {
        val prefix = domain.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00)
        return prefix + canonicalMessage
    }
}
