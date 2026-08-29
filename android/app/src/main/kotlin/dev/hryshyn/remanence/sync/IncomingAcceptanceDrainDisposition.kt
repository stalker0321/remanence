package dev.hryshyn.remanence.sync

/**
 * The action a future incoming-acceptance drain may take after one attempt.
 *
 * This is policy only. It does not mutate Room, delete files, or enqueue work.
 * [QUARANTINE_ELIGIBLE] is reserved for a future result carrying direct,
 * immutable per-capsule proof; no current aggregate rejection provides that
 * proof.
 */
enum class IncomingAcceptanceDrainDisposition {
    ACCEPTED,
    STOP_ACCOUNT,
    RETRY,
    QUARANTINE_ELIGIBLE,
    STOP_UNCLASSIFIED,
}

/** Pure, exhaustive policy for one already-completed acceptance attempt. */
object IncomingAcceptanceDrainClassifier {

    fun classify(
        result: IncomingCapsuleAcceptanceResult,
    ): IncomingAcceptanceDrainDisposition = when (result) {
        IncomingCapsuleAcceptanceResult.Committed,
        IncomingCapsuleAcceptanceResult.IdempotentReplay,
        -> IncomingAcceptanceDrainDisposition.ACCEPTED

        is IncomingCapsuleAcceptanceResult.Retryable ->
            IncomingAcceptanceDrainDisposition.RETRY

        is IncomingCapsuleAcceptanceResult.Rejected ->
            classifyRejection(result.reason)
    }

    private fun classifyRejection(
        reason: IncomingCapsuleAcceptanceRejectionReason,
    ): IncomingAcceptanceDrainDisposition = when (reason) {
        IncomingCapsuleAcceptanceRejectionReason.NO_AUTHENTICATED_OWNER,
        IncomingCapsuleAcceptanceRejectionReason.OWNER_MISMATCH,
        IncomingCapsuleAcceptanceRejectionReason.ACCOUNT_CHANGED,
        -> IncomingAcceptanceDrainDisposition.STOP_ACCOUNT

        // Recovery-temp cleanup is the coordinator's bounded, exact-file
        // repair boundary. A drain may retry it, but must not quarantine it.
        IncomingCapsuleAcceptanceRejectionReason.RECOVERY_TEMP_INVALID ->
            IncomingAcceptanceDrainDisposition.RETRY

        // These reasons intentionally remain fail-closed. They aggregate
        // local incompleteness, key/session history, storage, concurrency,
        // protocol, and server outcomes that are not immutable corruption
        // proof. In particular, never infer corruption from recipient-key
        // mismatch or unavailable key history.
        IncomingCapsuleAcceptanceRejectionReason.CAPSULE_METADATA_MISSING,
        IncomingCapsuleAcceptanceRejectionReason.ENVELOPE_METADATA_MISSING,
        IncomingCapsuleAcceptanceRejectionReason.RECOGNITION_METADATA_INVALID,
        IncomingCapsuleAcceptanceRejectionReason.CAPSULE_STATE_INVALID,
        IncomingCapsuleAcceptanceRejectionReason.TEMP_PATH_UNSAFE,
        IncomingCapsuleAcceptanceRejectionReason.DOWNLOAD_REJECTED,
        IncomingCapsuleAcceptanceRejectionReason.CRYPTO_REJECTED,
        IncomingCapsuleAcceptanceRejectionReason.PERSISTENCE_REJECTED,
        IncomingCapsuleAcceptanceRejectionReason.ADOPTION_REJECTED,
        IncomingCapsuleAcceptanceRejectionReason.ROOM_COMMIT_REJECTED,
        IncomingCapsuleAcceptanceRejectionReason.DURABLE_STATE_INVALID,
        -> IncomingAcceptanceDrainDisposition.STOP_UNCLASSIFIED
    }
}
