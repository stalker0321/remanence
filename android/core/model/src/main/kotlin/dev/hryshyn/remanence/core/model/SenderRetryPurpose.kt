package dev.hryshyn.remanence.core.model

import dev.hryshyn.remanence.protocol.v1.SenderRetryPurpose as SenderRetryPurposeProto

/**
 * M2-P08: the bounded, versioned purpose of a sender-owned wrapped
 * retry keyset (see [dev.hryshyn.remanence.protocol.v1.SenderRetryWrapContext]).
 *
 * Only [RECIPIENT_KEY_STALE_REWRAP] is currently defined and accepted
 * by the crypto layer. Any other value, including [UNSPECIFIED], fails
 * closed before any keyset material is ever serialized or decrypted.
 * New purposes MUST be appended here and explicitly accepted by the
 * `SenderRetryKeysetWrapper`.
 */
enum class SenderRetryPurpose {
    UNSPECIFIED,
    RECIPIENT_KEY_STALE_REWRAP;

    /** The wire value of this purpose in the protobuf enum. */
    val protoValue: Int
        get() = when (this) {
            UNSPECIFIED -> SenderRetryPurposeProto.SENDER_RETRY_PURPOSE_UNSPECIFIED_VALUE
            RECIPIENT_KEY_STALE_REWRAP -> SenderRetryPurposeProto.RECIPIENT_KEY_STALE_REWRAP_VALUE
        }

    /**
     * The protobuf enum value that must be set on the AAD context for
     * this purpose. Throws [IllegalArgumentException] for purposes the
     * crypto layer must never see on the wire.
     */
    internal fun toProto(): SenderRetryPurposeProto =
        when (this) {
            UNSPECIFIED -> SenderRetryPurposeProto.SENDER_RETRY_PURPOSE_UNSPECIFIED
            RECIPIENT_KEY_STALE_REWRAP -> SenderRetryPurposeProto.RECIPIENT_KEY_STALE_REWRAP
        }

    companion object {
        /**
         * The exhaustive set of purposes the crypto layer accepts as a
         * wrap / unwrap input. Any other value - including a future
         * not-yet-accepted purpose or a malformed wire value - fails
         * closed inside the encoder before any keyset material is read.
         */
        val ACCEPTED: Set<SenderRetryPurpose> = setOf(RECIPIENT_KEY_STALE_REWRAP)

        internal fun fromProto(value: SenderRetryPurposeProto): SenderRetryPurpose =
            when (value) {
                SenderRetryPurposeProto.SENDER_RETRY_PURPOSE_UNSPECIFIED -> UNSPECIFIED
                SenderRetryPurposeProto.RECIPIENT_KEY_STALE_REWRAP -> RECIPIENT_KEY_STALE_REWRAP
                else -> throw IllegalArgumentException(
                    "unsupported sender retry purpose wire value: $value",
                )
            }
    }
}
