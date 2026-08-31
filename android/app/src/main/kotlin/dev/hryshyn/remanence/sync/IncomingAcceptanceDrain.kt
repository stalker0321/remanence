package dev.hryshyn.remanence.sync

import dev.hryshyn.remanence.core.data.db.IncomingAcceptanceCandidate
import dev.hryshyn.remanence.core.data.db.IncomingAcceptanceCandidateSelection
import dev.hryshyn.remanence.core.data.db.IncomingAcceptanceCandidateSelector
import dev.hryshyn.remanence.core.data.db.IncomingCapsuleDao
import dev.hryshyn.remanence.core.data.db.IncomingCapsuleQuarantineResult
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Redacted outcomes of one bounded acceptance-drain invocation. */
sealed interface IncomingAcceptanceDrainResult {
    data class Completed(
        val progressCount: Int,
        val examinedCount: Int,
        val pageMayHaveMore: Boolean,
    ) : IncomingAcceptanceDrainResult {
        init {
            require(progressCount >= 0)
            require(examinedCount >= progressCount)
        }

        override fun toString(): String =
            "IncomingAcceptanceDrainResult.Completed(<redacted>)"
    }

    data class Retryable(val reason: IncomingAcceptanceDrainRetryReason) :
        IncomingAcceptanceDrainResult {
        override fun toString(): String =
            "IncomingAcceptanceDrainResult.Retryable(<redacted>)"
    }

    data object AccountStopped : IncomingAcceptanceDrainResult
}

enum class IncomingAcceptanceDrainRetryReason {
    SESSION_UNAVAILABLE,
    SELECTOR_UNAVAILABLE,
    ACCEPTANCE_UNAVAILABLE,
    ACCEPTANCE_RETRYABLE,
    QUARANTINE_UNAVAILABLE,
}

/**
 * A future immutable-validator capability. Current coordinator results do
 * not produce this type, so aggregate failures cannot authorize quarantine.
 */
internal class IncomingImmutableCapsuleInvalidityProof internal constructor(
    internal val ownerUserId: UserId,
    internal val capsuleId: CapsuleId,
) {
    override fun toString(): String =
        "IncomingImmutableCapsuleInvalidityProof(<redacted>)"
}

/** One typed attempt result; quarantine is impossible without the proof type. */
internal sealed interface IncomingAcceptanceDrainAttempt {
    data class Acceptance(val result: IncomingCapsuleAcceptanceResult) :
        IncomingAcceptanceDrainAttempt {
        override fun toString(): String =
            "IncomingAcceptanceDrainAttempt.Acceptance(<redacted>)"
    }

    data class ProvenImmutableInvalidity(
        val proof: IncomingImmutableCapsuleInvalidityProof,
    ) : IncomingAcceptanceDrainAttempt {
        override fun toString(): String =
            "IncomingAcceptanceDrainAttempt.ProvenImmutableInvalidity(<redacted>)"
    }
}

/** Internal seam keeping the runner deterministic without replacing production policy. */
internal fun interface IncomingAcceptanceDrainAttemptPort {
    suspend fun attempt(request: IncomingCapsuleAcceptanceRequest): IncomingAcceptanceDrainAttempt
}

/** Internal seam for the exact c2 owner-scoped CAS. */
internal fun interface IncomingAcceptanceDrainQuarantinePort {
    suspend fun quarantine(
        ownerUserId: UserId,
        capsuleId: CapsuleId,
        proof: IncomingImmutableCapsuleInvalidityProof,
    ): IncomingCapsuleQuarantineResult
}

/** Small adapter around the accepted deterministic Room selector. */
internal fun interface IncomingAcceptanceCandidateSource {
    suspend fun select(
        ownerUserId: UserId,
        limit: Int,
    ): IncomingAcceptanceCandidateSelection
}

internal fun effectiveIncomingAcceptanceDrainBound(
    requested: Int,
    selectorMaxPageSize: Int,
): Int {
    require(requested in 1..IncomingAcceptanceDrain.MAX_CANDIDATES_PER_RUN) {
        "acceptance drain bound is invalid"
    }
    require(selectorMaxPageSize in 1..IncomingAcceptanceDrain.MAX_CANDIDATES_PER_RUN) {
        "candidate selector bound is invalid"
    }
    return minOf(requested, selectorMaxPageSize)
}

/**
 * One bounded, sequential acceptance-drain invocation. It owns no cursor or
 * durable progress state; Room material states remain the restart source of
 * truth.
 */
class IncomingAcceptanceDrain internal constructor(
    private val candidates: IncomingAcceptanceCandidateSource,
    private val currentOwner: suspend () -> UserId?,
    private val attempt: IncomingAcceptanceDrainAttemptPort,
    private val quarantine: IncomingAcceptanceDrainQuarantinePort,
    private val maxCandidatesPerRun: Int = MAX_CANDIDATES_PER_RUN,
) {

    /** Production-shaped constructor; no worker or scheduler wiring is added here. */
    internal constructor(
        selector: IncomingAcceptanceCandidateSelector,
        currentOwner: suspend () -> UserId?,
        acceptanceCoordinator: IncomingCapsuleAcceptanceCoordinator,
        quarantineDao: IncomingCapsuleDao,
        maxCandidatesPerRun: Int = MAX_CANDIDATES_PER_RUN,
    ) : this(
        candidates = IncomingAcceptanceCandidateSource { owner, limit ->
            selector.select(ownerUserId = owner, after = null, limit = limit)
        },
        currentOwner = currentOwner,
        attempt = IncomingAcceptanceDrainAttemptPort { request ->
            IncomingAcceptanceDrainAttempt.Acceptance(acceptanceCoordinator.accept(request))
        },
        quarantine = IncomingAcceptanceDrainQuarantinePort { owner, capsule, proof ->
            if (proof.ownerUserId != owner || proof.capsuleId != capsule) {
                IncomingCapsuleQuarantineResult.ConcurrentOrStateChanged
            } else {
                quarantineDao.quarantineReadyDiscoveredForOwner(
                    ownerUserId = owner.toRestString(),
                    capsuleId = capsule.toRestString(),
                )
            }
        },
        maxCandidatesPerRun = effectiveIncomingAcceptanceDrainBound(
            requested = maxCandidatesPerRun,
            selectorMaxPageSize = selector.maxPageSize,
        ),
    )

    init {
        require(maxCandidatesPerRun in 1..MAX_CANDIDATES_PER_RUN) {
            "acceptance drain bound is invalid"
        }
    }

    suspend fun run(expectedOwner: UserId? = null): IncomingAcceptanceDrainResult {
        coroutineContext.ensureActive()
        IncomingAcceptanceDiagnostics.report("acceptance started")

        val owner = when (val initial = readOwner()) {
            is OwnerStatus.Ready -> initial.owner
            OwnerStatus.Stopped -> return IncomingAcceptanceDrainResult.AccountStopped
            OwnerStatus.Unavailable ->
                return IncomingAcceptanceDrainResult.Retryable(
                    IncomingAcceptanceDrainRetryReason.SESSION_UNAVAILABLE,
                )
        }
        if (expectedOwner != null && owner != expectedOwner) {
            return IncomingAcceptanceDrainResult.AccountStopped
        }
        when (verifyOwner(owner)) {
            is OwnerStatus.Ready -> Unit
            OwnerStatus.Stopped -> return IncomingAcceptanceDrainResult.AccountStopped
            OwnerStatus.Unavailable ->
                return IncomingAcceptanceDrainResult.Retryable(
                    IncomingAcceptanceDrainRetryReason.SESSION_UNAVAILABLE,
                )
        }

        val selection = try {
            candidates.select(owner, maxCandidatesPerRun)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            IncomingAcceptanceDiagnostics.report("acceptance selector exception")
            return IncomingAcceptanceDrainResult.Retryable(
                IncomingAcceptanceDrainRetryReason.SELECTOR_UNAVAILABLE,
            )
        }
        val page = when (selection) {
            is IncomingAcceptanceCandidateSelection.Page -> selection
            // The accepted selector currently aggregates malformed durable
            // identity and operational failure. Until that seam is split,
            // retry conservatively without mutating or quarantining anything.
            IncomingAcceptanceCandidateSelection.InvalidRequest,
            IncomingAcceptanceCandidateSelection.Unavailable,
            -> {
                IncomingAcceptanceDiagnostics.report("acceptance selector unavailable")
                return IncomingAcceptanceDrainResult.Retryable(
                    IncomingAcceptanceDrainRetryReason.SELECTOR_UNAVAILABLE,
                )
            }
        }
        IncomingAcceptanceDiagnostics.report(
            if (page.candidates.isEmpty()) "no acceptance candidate" else "acceptance candidate found",
        )

        // A correct Room selector is already bounded. Keeping this defensive
        // cap makes the runner bounded even when an injected source misbehaves.
        val pageCandidates = page.candidates.take(maxCandidatesPerRun)
        var progressCount = 0
        var examinedCount = 0
        for (candidate in pageCandidates) {
            coroutineContext.ensureActive()
            when (verifyOwner(owner)) {
                is OwnerStatus.Ready -> Unit
                OwnerStatus.Stopped -> return IncomingAcceptanceDrainResult.AccountStopped
                OwnerStatus.Unavailable ->
                    return IncomingAcceptanceDrainResult.Retryable(
                        IncomingAcceptanceDrainRetryReason.SESSION_UNAVAILABLE,
                    )
            }
            examinedCount += 1

            val candidateAttempt = try {
                attempt.attempt(
                    IncomingCapsuleAcceptanceRequest(
                        ownerUserId = owner,
                        capsuleId = candidate.capsuleId,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                IncomingAcceptanceDiagnostics.report("acceptance exception")
                return IncomingAcceptanceDrainResult.Retryable(
                    IncomingAcceptanceDrainRetryReason.ACCEPTANCE_UNAVAILABLE,
                )
            }

            when (candidateAttempt) {
                is IncomingAcceptanceDrainAttempt.Acceptance -> {
                    IncomingAcceptanceDiagnostics.report(
                        when (val result = candidateAttempt.result) {
                            IncomingCapsuleAcceptanceResult.Committed -> "acceptance committed"
                            IncomingCapsuleAcceptanceResult.IdempotentReplay -> "acceptance replayed"
                            is IncomingCapsuleAcceptanceResult.Retryable ->
                                "acceptance retry: ${result.reason.name}"
                            is IncomingCapsuleAcceptanceResult.Rejected ->
                                "acceptance rejected: ${result.reason.name}"
                        },
                    )
                    when (IncomingAcceptanceDrainClassifier.classify(candidateAttempt.result)) {
                        IncomingAcceptanceDrainDisposition.ACCEPTED -> progressCount += 1
                        IncomingAcceptanceDrainDisposition.RETRY ->
                            return IncomingAcceptanceDrainResult.Retryable(
                                IncomingAcceptanceDrainRetryReason.ACCEPTANCE_RETRYABLE,
                            )
                        IncomingAcceptanceDrainDisposition.STOP_ACCOUNT ->
                            return IncomingAcceptanceDrainResult.AccountStopped
                        IncomingAcceptanceDrainDisposition.STOP_UNCLASSIFIED,
                        // No current aggregate result carries proof. This
                        // branch is deliberately a no-mutation skip.
                        IncomingAcceptanceDrainDisposition.QUARANTINE_ELIGIBLE,
                        -> Unit
                    }
                }

                is IncomingAcceptanceDrainAttempt.ProvenImmutableInvalidity -> {
                    val proof = candidateAttempt.proof
                    if (proof.ownerUserId != owner || proof.capsuleId != candidate.capsuleId) {
                        // A proof for another owner/capsule is not consumed and
                        // cannot authorize a mutation for this candidate.
                        continue
                    }
                    when (verifyOwner(owner)) {
                        is OwnerStatus.Ready -> Unit
                        OwnerStatus.Stopped -> return IncomingAcceptanceDrainResult.AccountStopped
                        OwnerStatus.Unavailable ->
                            return IncomingAcceptanceDrainResult.Retryable(
                                IncomingAcceptanceDrainRetryReason.SESSION_UNAVAILABLE,
                            )
                    }
                    val quarantineResult = try {
                        quarantine.quarantine(owner, candidate.capsuleId, proof)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        return IncomingAcceptanceDrainResult.Retryable(
                            IncomingAcceptanceDrainRetryReason.QUARANTINE_UNAVAILABLE,
                        )
                    }
                    when (quarantineResult) {
                        IncomingCapsuleQuarantineResult.Quarantined,
                        IncomingCapsuleQuarantineResult.AlreadyCorrupt,
                        -> progressCount += 1
                        IncomingCapsuleQuarantineResult.DatabaseUnavailable ->
                            return IncomingAcceptanceDrainResult.Retryable(
                                IncomingAcceptanceDrainRetryReason.QUARANTINE_UNAVAILABLE,
                            )
                        // Both outcomes are safe no-mutation skips. They do
                        // not poison this page and are reconsidered later.
                        IncomingCapsuleQuarantineResult.MissingOrForeignOwner,
                        IncomingCapsuleQuarantineResult.ConcurrentOrStateChanged,
                        -> Unit
                    }
                }
            }
        }

        return IncomingAcceptanceDrainResult.Completed(
            progressCount = progressCount,
            examinedCount = examinedCount,
            pageMayHaveMore = page.candidates.size >= maxCandidatesPerRun,
        )
    }

    private suspend fun readOwner(): OwnerStatus = try {
        when (val owner = currentOwner()) {
            null -> OwnerStatus.Stopped
            else -> OwnerStatus.Ready(owner)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        OwnerStatus.Unavailable
    }

    private suspend fun verifyOwner(expected: UserId): OwnerStatus = try {
        when (val observed = currentOwner()) {
            null -> OwnerStatus.Stopped
            expected -> OwnerStatus.Ready(observed)
            else -> OwnerStatus.Stopped
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        OwnerStatus.Unavailable
    }

    private sealed interface OwnerStatus {
        data class Ready(val owner: UserId) : OwnerStatus
        data object Stopped : OwnerStatus
        data object Unavailable : OwnerStatus
    }

    companion object {
        const val MAX_CANDIDATES_PER_RUN: Int = 32
    }
}
