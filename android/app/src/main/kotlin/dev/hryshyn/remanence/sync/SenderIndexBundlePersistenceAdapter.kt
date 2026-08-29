package dev.hryshyn.remanence.sync

import dev.hryshyn.remanence.index.SenderIndexBundleStageFailure
import dev.hryshyn.remanence.index.SenderIndexBundleStageRequest
import dev.hryshyn.remanence.index.SenderIndexBundleStageResult
import dev.hryshyn.remanence.index.SenderIndexBundleStager
import dev.hryshyn.remanence.core.model.UserId
import kotlin.coroutines.cancellation.CancellationException

/**
 * A12b1's production binding for the mandatory A12 persistence port. The
 * stager owns plaintext construction, sealing, and durable replay; this
 * adapter only binds the authenticated owner/capsule and maps its redacted
 * outcome to the already-established A11d1 contract.
 */
class SenderIndexBundlePersistenceAdapter internal constructor(
    private val stage: suspend (SenderIndexBundleStageRequest) -> SenderIndexBundleStageResult,
) : IncomingVerifiedControlIndexPersistencePort {

    /** Production construction; no other storage or crypto path is introduced. */
    constructor(stager: SenderIndexBundleStager) : this(stager::stage)

    override suspend fun persist(
        request: IncomingVerifiedControlIndexPersistenceRequest,
        authenticatedOwnerUserId: UserId,
    ): IncomingVerifiedControlIndexPersistenceResult {
        val stageRequest = SenderIndexBundleStageRequest(
            authenticatedOwnerUserId = authenticatedOwnerUserId,
            ownerUserId = request.ownerUserId,
            capsuleId = request.capsuleId,
            verifiedRecognition = request.verified.recognition,
        )
        val staged = try {
            stage(stageRequest)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return IncomingVerifiedControlIndexPersistenceResult.Retryable(
                IncomingVerifiedControlIndexPersistenceRetryReason.LOCAL_STORAGE,
            )
        }

        return when (staged) {
            is SenderIndexBundleStageResult.Staged ->
                // The existing A11d1 port intentionally exposes no path or
                // capability. Both a fresh stage and a semantic replay are
                // durable success at this boundary.
                IncomingVerifiedControlIndexPersistenceResult.Durable
            is SenderIndexBundleStageResult.Failure -> mapFailure(staged)
        }
    }

    private fun mapFailure(
        failure: SenderIndexBundleStageResult.Failure,
    ): IncomingVerifiedControlIndexPersistenceResult {
        return when (failure.reason) {
            SenderIndexBundleStageFailure.NO_AUTHENTICATED_OWNER,
            SenderIndexBundleStageFailure.OWNER_MISMATCH,
            -> rejected(IncomingVerifiedControlIndexPersistenceRejectionReason.OWNER_MISMATCH)

            SenderIndexBundleStageFailure.INVALID_VERIFIED_RECOGNITION,
            SenderIndexBundleStageFailure.PATH_UNSAFE,
            SenderIndexBundleStageFailure.DESTINATION_CONFLICT,
            SenderIndexBundleStageFailure.ATOMIC_MOVE_UNAVAILABLE,
            SenderIndexBundleStageFailure.DURABILITY_UNAVAILABLE,
            -> if (failure.retryable) {
                retryable()
            } else {
                rejected(IncomingVerifiedControlIndexPersistenceRejectionReason.INVALID_VERIFIED_PAYLOAD)
            }

            SenderIndexBundleStageFailure.SEALING_FAILED,
            SenderIndexBundleStageFailure.LOCAL_STORAGE,
            -> if (failure.retryable) {
                retryable()
            } else {
                rejected(IncomingVerifiedControlIndexPersistenceRejectionReason.INVALID_VERIFIED_PAYLOAD)
            }
        }
    }

    private fun retryable() = IncomingVerifiedControlIndexPersistenceResult.Retryable(
        IncomingVerifiedControlIndexPersistenceRetryReason.LOCAL_STORAGE,
    )

    private fun rejected(reason: IncomingVerifiedControlIndexPersistenceRejectionReason) =
        IncomingVerifiedControlIndexPersistenceResult.Rejected(reason)
}
