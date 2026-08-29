package dev.hryshyn.remanence.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class IncomingAcceptanceDrainDispositionTest {

    @Test
    fun everyCurrentResultHasAnExplicitDisposition() {
        val cases = buildList {
            add(
                Case(
                    "committed",
                    IncomingCapsuleAcceptanceResult.Committed,
                    IncomingAcceptanceDrainDisposition.ACCEPTED,
                ),
            )
            add(
                Case(
                    "idempotent-replay",
                    IncomingCapsuleAcceptanceResult.IdempotentReplay,
                    IncomingAcceptanceDrainDisposition.ACCEPTED,
                ),
            )
            IncomingCapsuleAcceptanceRetryReason.entries.forEach { reason ->
                add(
                    Case(
                        "retryable/$reason",
                        IncomingCapsuleAcceptanceResult.Retryable(reason),
                        expectedRetryable(reason),
                    ),
                )
            }
            IncomingCapsuleAcceptanceRejectionReason.entries.forEach { reason ->
                add(
                    Case(
                        "rejected/$reason",
                        IncomingCapsuleAcceptanceResult.Rejected(reason),
                        expectedRejected(reason),
                    ),
                )
            }
        }

        cases.forEach { case ->
            assertEquals(
                "unexpected drain policy for ${case.name}",
                case.expected,
                IncomingAcceptanceDrainClassifier.classify(case.result),
            )
        }
    }

    @Test
    fun accountAndOperationalFailuresNeverRequestQuarantine() {
        val cases = buildList<IncomingCapsuleAcceptanceResult> {
            IncomingCapsuleAcceptanceRetryReason.entries.forEach { reason ->
                add(IncomingCapsuleAcceptanceResult.Retryable(reason))
            }
            IncomingCapsuleAcceptanceRejectionReason.entries.forEach { reason ->
                add(IncomingCapsuleAcceptanceResult.Rejected(reason))
            }
        }

        cases.forEach { result ->
            assertNotEquals(
                "account/operational/ambiguous failure must not request quarantine",
                IncomingAcceptanceDrainDisposition.QUARANTINE_ELIGIBLE,
                IncomingAcceptanceDrainClassifier.classify(result),
            )
        }
    }

    @Test
    fun successfulResultsAreAcceptedAndRecoveryTempIsRetryable() {
        assertEquals(
            IncomingAcceptanceDrainDisposition.ACCEPTED,
            IncomingAcceptanceDrainClassifier.classify(
                IncomingCapsuleAcceptanceResult.Committed,
            ),
        )
        assertEquals(
            IncomingAcceptanceDrainDisposition.ACCEPTED,
            IncomingAcceptanceDrainClassifier.classify(
                IncomingCapsuleAcceptanceResult.IdempotentReplay,
            ),
        )
        assertEquals(
            IncomingAcceptanceDrainDisposition.RETRY,
            IncomingAcceptanceDrainClassifier.classify(
                IncomingCapsuleAcceptanceResult.Rejected(
                    IncomingCapsuleAcceptanceRejectionReason.RECOVERY_TEMP_INVALID,
                ),
            ),
        )
    }

    private fun expectedRetryable(
        reason: IncomingCapsuleAcceptanceRetryReason,
    ): IncomingAcceptanceDrainDisposition = when (reason) {
        IncomingCapsuleAcceptanceRetryReason.SESSION_UNAVAILABLE,
        IncomingCapsuleAcceptanceRetryReason.LOCAL_STORAGE,
        IncomingCapsuleAcceptanceRetryReason.DOWNLOAD,
        IncomingCapsuleAcceptanceRetryReason.CRYPTO_ACCEPTANCE,
        IncomingCapsuleAcceptanceRetryReason.VERIFIED_PAYLOAD_PERSISTENCE,
        IncomingCapsuleAcceptanceRetryReason.ADOPTION,
        IncomingCapsuleAcceptanceRetryReason.ROOM_COMMIT,
        -> IncomingAcceptanceDrainDisposition.RETRY
    }

    private fun expectedRejected(
        reason: IncomingCapsuleAcceptanceRejectionReason,
    ): IncomingAcceptanceDrainDisposition = when (reason) {
        IncomingCapsuleAcceptanceRejectionReason.NO_AUTHENTICATED_OWNER,
        IncomingCapsuleAcceptanceRejectionReason.OWNER_MISMATCH,
        IncomingCapsuleAcceptanceRejectionReason.ACCOUNT_CHANGED,
        -> IncomingAcceptanceDrainDisposition.STOP_ACCOUNT

        IncomingCapsuleAcceptanceRejectionReason.RECOVERY_TEMP_INVALID ->
            IncomingAcceptanceDrainDisposition.RETRY

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

    private data class Case(
        val name: String,
        val result: IncomingCapsuleAcceptanceResult,
        val expected: IncomingAcceptanceDrainDisposition,
    )
}
