package dev.hryshyn.remanence.ui.scan

import dev.hryshyn.remanence.core.data.db.IncomingCapsuleDao
import dev.hryshyn.remanence.core.data.db.IncomingSenderIndexCandidate
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.FingerprintCodec
import dev.hryshyn.remanence.core.recognition.IndexedCandidate
import dev.hryshyn.remanence.index.SenderIndexBundleReadRequest
import dev.hryshyn.remanence.index.SenderIndexBundleReadResult
import dev.hryshyn.remanence.index.SenderIndexBundleInspectionSnapshot
import dev.hryshyn.remanence.index.SenderIndexBundleReader
import dev.hryshyn.remanence.ui.capsule.CapsulePresentationSource
import kotlin.coroutines.cancellation.CancellationException

/** The only ephemeral chooser material retained for one scan generation. */
internal data class ScanChooserHint(
    val candidateId: String,
    val senderHandleSnapshot: String,
    val createdAtEpochSeconds: Long,
    val placeLabel: String?,
) {
    override fun toString(): String = "ScanChooserHint(<redacted>)"
}

/** Sender-index candidates and their scan-scoped, already-decrypted hints. */
internal data class ScanCandidateIndex(
    val candidates: List<IndexedCandidate>,
    val chooserHints: Map<String, ScanChooserHint> = emptyMap(),
    /** Ephemeral presentation-plane binding, independent of CandidateOrigin. */
    val presentationSources: Map<java.util.UUID, CapsulePresentationSource> = emptyMap(),
) {
    override fun toString(): String = "ScanCandidateIndex(<redacted>)"

    companion object {
        val EMPTY = ScanCandidateIndex(emptyList())
    }
}

/**
 * Binds storage origin only from explicit owner-scoped membership facts. A
 * dual incoming/outbox identity is intentionally ambiguous and is rejected;
 * recognition preference never supplies a missing source.
 */
internal fun resolvePresentationSources(
    candidateIds: Iterable<java.util.UUID>,
    incomingSources: Map<java.util.UUID, CapsulePresentationSource>,
    roomSources: Map<java.util.UUID, CapsulePresentationSource>,
): Map<java.util.UUID, CapsulePresentationSource> {
    val resolved = LinkedHashMap<java.util.UUID, CapsulePresentationSource>()
    for (candidateId in candidateIds) {
        val incoming = incomingSources[candidateId]
        val room = roomSources[candidateId]
        // Membership is a provenance fact, not an enum value supplied by the
        // caller. Two non-null planes are ambiguous even if their labels
        // happen to be equal, so never publish either binding.
        if (incoming != null && room != null) continue
        (incoming ?: room)?.let { resolved[candidateId] = it }
    }
    return resolved
}

/**
 * Production bridge from accepted incoming Room identities to A12's sealed
 * sender-index files. It never exposes a path or stores decrypted index data.
 */
internal class IncomingSenderIndexCandidateProvider(
    private val incomingCapsuleDao: IncomingCapsuleDao,
    private val senderIndexBundleReader: SenderIndexBundleReader,
    private val currentOwner: suspend () -> UserId?,
) {

    /** Loads only the authenticated owner's accepted sender-index candidates. */
    suspend fun load(ownerUserId: UserId): ScanCandidateIndex {
        if (currentOwner() != ownerUserId) return ScanCandidateIndex.EMPTY

        val selected = incomingCapsuleDao.selectSenderIndexCandidatesForOwner(ownerUserId)
        val candidates = ArrayList<IndexedCandidate>(selected.size)
        val hints = LinkedHashMap<String, ScanChooserHint>(selected.size)
        val presentationSources = LinkedHashMap<java.util.UUID, CapsulePresentationSource>(selected.size)
        for (candidate in selected) {
            // The incoming Room row proves the storage plane independently of
            // whether its encrypted index can be read. A recipient baseline
            // for the same capsule must still prepare from incoming storage.
            presentationSources[candidate.capsuleId.value] = CapsulePresentationSource.INCOMING
            if (currentOwner() != ownerUserId) return ScanCandidateIndex.EMPTY
            val loaded = readCandidate(ownerUserId, candidate) ?: continue
            candidates += loaded.first
            hints[loaded.first.capsuleId.toString()] = loaded.second
            presentationSources[loaded.first.capsuleId] = CapsulePresentationSource.INCOMING
        }
        if (currentOwner() != ownerUserId) return ScanCandidateIndex.EMPTY
        return ScanCandidateIndex(
            candidates = candidates,
            chooserHints = hints,
            presentationSources = presentationSources,
        )
    }

    private suspend fun readCandidate(
        authenticatedOwner: UserId,
        candidate: IncomingSenderIndexCandidate,
    ): Pair<IndexedCandidate, ScanChooserHint>? {
        if (candidate.ownerUserId != authenticatedOwner) return null

        var snapshot: SenderIndexBundleInspectionSnapshot? = null
        var frontBytes: ByteArray? = null
        var primaryFailure: Throwable? = null
        try {
            val result = senderIndexBundleReader.inspect(
                SenderIndexBundleReadRequest(
                    authenticatedOwnerUserId = authenticatedOwner,
                    ownerUserId = candidate.ownerUserId,
                    capsuleId = candidate.capsuleId,
                ),
            )
            if (result !is SenderIndexBundleReadResult.Available) return null
            snapshot = result.snapshot
            if (snapshot.capsuleId != candidate.capsuleId) return null

            frontBytes = snapshot.frontFingerprint
            val front = FingerprintCodec.parse(frontBytes!!)
            val capsuleId = candidate.capsuleId.toString()
            return IndexedCandidate(
                capsuleId = candidate.capsuleId.value,
                front = front,
                // These are the sender's fingerprints, not a recipient baseline.
                recipientPreferred = false,
            ) to ScanChooserHint(
                candidateId = capsuleId,
                senderHandleSnapshot = snapshot.senderHandleSnapshot,
                createdAtEpochSeconds = snapshot.createdAtEpochSeconds,
                placeLabel = snapshot.placeLabel,
            )
        } catch (failure: Throwable) {
            primaryFailure = failure
            if (failure is CancellationException) throw failure
            return null
        } finally {
            frontBytes?.fill(0)
            snapshot?.let { closeSnapshot(it, primaryFailure) }
        }
    }

    private fun closeSnapshot(
        snapshot: SenderIndexBundleInspectionSnapshot,
        primaryFailure: Throwable?,
    ) {
        try {
            snapshot.close()
        } catch (cleanupFailure: Throwable) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(cleanupFailure)
            } else {
                throw cleanupFailure
            }
        }
    }
}
