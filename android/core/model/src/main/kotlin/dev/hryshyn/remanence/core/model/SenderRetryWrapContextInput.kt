package dev.hryshyn.remanence.core.model

/**
 * M2-P08: typed input for the deterministic sender-retry wrap AAD. The
 * `protocol_version`, `ownerUserId`, `capsuleId`, `senderKeyBundleId`,
 * and `purpose` fields are bound into a deterministic protobuf message
 * by [CryptoContextEncoder.senderRetryWrapAad] and used as the AEAD
 * associated data for the `SenderRetryKeysetWrapper`. Unwrap requires
 * the caller to supply an equal-valued input so a wrapped keyset can
 * only ever be opened for the capsule + sender bundle + purpose it
 * was originally wrapped for.
 *
 * The construction refuses every purpose other than
 * [SenderRetryPurpose.RECIPIENT_KEY_STALE_REWRAP], including the
 * sentinel [SenderRetryPurpose.UNSPECIFIED] and any future purpose the
 * crypto layer has not been told to accept. The contract is fail
 * closed at the data-class boundary, not after crypto work has
 * started.
 */
data class SenderRetryWrapContextInput(
    val ownerUserId: UserId,
    val capsuleId: CapsuleId,
    val senderKeyBundleId: KeyBundleId,
    val purpose: SenderRetryPurpose,
) {
    init {
        require(purpose in SenderRetryPurpose.ACCEPTED) {
            "only ${SenderRetryPurpose.ACCEPTED} are accepted; got $purpose"
        }
    }
}
