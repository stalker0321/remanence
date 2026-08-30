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
) {
    override fun toString(): String = "ScanCandidateIndex(<redacted>)"

    companion object {
        val EMPTY = ScanCandidateIndex(emptyList())
    }
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
        for (candidate in selected) {
            if (currentOwner() != ownerUserId) return ScanCandidateIndex.EMPTY
            val loaded = readCandidate(ownerUserId, candidate) ?: continue
            candidates += loaded.first
            hints[loaded.first.capsuleId.toString()] = loaded.second
        }
        if (currentOwner() != ownerUserId) return ScanCandidateIndex.EMPTY
        return ScanCandidateIndex(candidates = candidates, chooserHints = hints)
    }

    private suspend fun readCandidate(
        authenticatedOwner: UserId,
        candidate: IncomingSenderIndexCandidate,
    ): Pair<IndexedCandidate, ScanChooserHint>? {
        if (candidate.ownerUserId != authenticatedOwner) return null

        var snapshot: SenderIndexBundleInspectionSnapshot? = null
        var frontBytes: ByteArray? = null
        var backBytes: ByteArray? = null
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
            backBytes = snapshot.backFingerprint
            val front = FingerprintCodec.parse(frontBytes!!)
            val back = FingerprintCodec.parse(backBytes!!)
            val capsuleId = candidate.capsuleId.toString()
            return IndexedCandidate(
                capsuleId = candidate.capsuleId.value,
                front = front,
                back = back,
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
            backBytes?.fill(0)
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
