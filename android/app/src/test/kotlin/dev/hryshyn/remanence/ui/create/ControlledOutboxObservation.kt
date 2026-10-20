package dev.hryshyn.remanence.ui.create

import dev.hryshyn.remanence.core.data.db.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Only observation delivery is controlled. Staging and row reads use Room. */
internal class ControlledOutboxObservation(private val room: OutboxCapsuleDao) : OutboxCapsuleDao() {
    private data class Emission(val status: OutboxCapsuleStatus?, val applied: CompletableDeferred<Unit>)
    private val streams = mutableMapOf<Pair<String, String>, Channel<Emission>>()
    private val lateAfterCancellation = mutableMapOf<Pair<String, String>, Channel<Emission>>()

    override fun observeStatusByCapsuleIdAndOwner(capsuleId: String, ownerUserId: String): Flow<OutboxCapsuleStatus?> =
        object : Flow<OutboxCapsuleStatus?> {
        override suspend fun collect(collector: FlowCollector<OutboxCapsuleStatus?>) {
            val key = capsuleId to ownerUserId
            val channel = Channel<Emission>(Channel.UNLIMITED)
            check(streams.put(key, channel) == null)
            try {
                for (event in channel) {
                    collector.emit(event.status)
                    event.applied.complete(Unit)
                }
            } finally {
                // Explicit hostile-source control: once armed, deliver exactly
                // one old event even after cancellation. No product test hook.
                lateAfterCancellation.remove(key)?.let { late -> withContext(NonCancellable) {
                    val event = late.receive()
                    collector.emit(event.status)
                    event.applied.complete(Unit)
                } }
            }
        }
    }

    fun armLate(capsule: String, owner: String): suspend (OutboxCapsuleStatus?) -> Unit {
        val channel = Channel<Emission>(1)
        check(lateAfterCancellation.put(capsule to owner, channel) == null)
        return { status ->
            val applied = CompletableDeferred<Unit>()
            channel.send(Emission(status, applied))
            applied.await()
        }
    }

    suspend fun snapshot(capsule: String, owner: String): OutboxCapsuleStatus? =
        room.getByCapsuleIdAndOwner(capsule, owner)?.let { OutboxCapsuleStatus(it.state, it.lastErrorCode) }

    suspend fun deliver(capsule: String, owner: String, status: OutboxCapsuleStatus?) {
        val applied = CompletableDeferred<Unit>()
        requireNotNull(streams[capsule to owner]).send(Emission(status, applied))
        applied.await() // Return only after the real VM collector has applied it.
    }

    override suspend fun getByCapsuleIdAndOwner(capsuleId: String, ownerUserId: String) =
        room.getByCapsuleIdAndOwner(capsuleId, ownerUserId)
    // Only explicit confirmed-quota retry may release this persisted marker.
    override suspend fun releaseSendLimitForOwner(capsuleId: String, ownerUserId: String) =
        room.releaseSendLimitForOwner(capsuleId, ownerUserId)
    override suspend fun getByCapsuleIdsAndOwner(capsuleIds: List<String>, ownerUserId: String) =
        room.getByCapsuleIdsAndOwner(capsuleIds, ownerUserId)

    // VM observation/retry admission is read-only. Any unexpected write is a
    // test failure; the publisher's real CapsuleOutboxStager uses Room itself.
    override suspend fun insertStrict(capsule: OutboxCapsuleEntity): Unit = error("unexpected observer write")
    override suspend fun findOwnersOfImmutableIds(capsuleId: String, idempotencyKey: String): List<String> = error("unexpected probe")
    override suspend fun clearForOwner(ownerUserId: String): Unit = error("unexpected observer write")
    override suspend fun getCapsuleIdsNeedingUploadForOwner(ownerUserId: String): List<String> = error("unexpected resumer")
    override suspend fun markEncryptedForOwner(capsuleId: String, ownerUserId: String): Int = error("unexpected observer write")
    override suspend fun beginUploadForOwner(capsuleId: String, ownerUserId: String): Int = error("unexpected observer write")
    override suspend fun beginFinalizeForOwner(capsuleId: String, ownerUserId: String): Int = error("unexpected observer write")
    override suspend fun markPublishedForOwner(capsuleId: String, ownerUserId: String): Int = error("unexpected observer write")
    override suspend fun markFinalizeRetryableForOwner(capsuleId: String, ownerUserId: String, errorCode: String?): Int = error("unexpected observer write")
    override suspend fun markRetryableFailureForOwner(capsuleId: String, ownerUserId: String, errorCode: String?): Int = error("unexpected observer write")
    override suspend fun markTerminalFailureForOwner(capsuleId: String, ownerUserId: String, errorCode: String?): Int = error("unexpected observer write")
    override suspend fun clearSenderRetryKeysetPath(capsuleId: String, ownerUserId: String, expectedPath: String?): Int = error("unexpected observer write")
    override suspend fun applyDraftRecipientKeyRewrapForOwner(capsuleId: String, ownerUserId: String, recipientUserId: String,
        expectedRecipientKeyBundleId: String, newRecipientKeyBundleId: String, newEnvelopePath: String,
        newPublishStatementPath: String, newPublishStatementSignaturePath: String): Int = error("unexpected rewrap")
    override suspend fun applyFinalizeRecipientKeyRewrapForOwner(capsuleId: String, ownerUserId: String, recipientUserId: String,
        expectedRecipientKeyBundleId: String, newRecipientKeyBundleId: String, newEnvelopePath: String,
        newPublishStatementPath: String, newPublishStatementSignaturePath: String): Int = error("unexpected rewrap")
}
