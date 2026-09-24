package dev.hryshyn.remanence.create

import dev.hryshyn.remanence.core.crypto.readBoundedBytes
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.core.model.GeneratorStaging
import dev.hryshyn.remanence.core.model.UserId
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * G4-C1 — pure/injected Create lifecycle bridge: binds one generation
 * from client-owned [PhotoSource] handles through G4B verification into
 * G3 staging leases, with immutable-context gating on every operation.
 *
 * Scope (strict): immutable [GenerationContext] capture; G3 session
 * open/use via an injected [GeneratorStaging.Manager]; G4B binding via
 * an injected [GeneratorSourceBinding.SourceBinder]; per-callback
 * context validation with stale/duplicate rejection; invalidation and
 * revoke on photo/note edits, owner switch, epoch change, cancel and
 * logout; staging cleanup on failure/cancellation; frozen validated
 * handoff for later `startPublishing` (never called here) with one-shot
 * consume semantics (a second freeze is rejected, late binds go stale).
 *
 * Explicitly NOT in C1: CreateViewModel/AppContainer/UI/navigation
 * wiring, publisher calls, protocol changes, filesystem-store work,
 * renderer algorithms, M4. `createSessionGeneration` NEVER substitutes
 * for [GenerationContext.contentRevision]: the bridge owns a separate
 * monotonic revision counter, and process restart is represented by a
 * new epoch with old-context rejection.
 *
 * Purity: no Android classes, no filesystem paths, no clock reads
 * (G3 owns time/TTL); all byte movement is in-memory. Cancellation
 * ([CancellationException]) always propagates; sources are closed by
 * `use {}` on every path.
 *
 * Concurrency: all bridge gates and record transitions are serialized on
 * one private lock ([synchronized]), while byte IO and binder work run
 * outside it. Same-session duplicate/stale/order gates plus an in-flight
 * ordinal reservation are therefore atomic and always evaluated before
 * any source is opened: two concurrent binds of one ordinal cannot both
 * pass, and at most one of them ever opens its source. Invalidators
 * revoke and drop a record only on exact context match, so a
 * stale/mismatched cancel or edit can neither drop a live record nor
 * orphan G3 bytes. Lock order is always bridge → G3 (never the reverse),
 * so the synchronous G3 calls under the bridge lock cannot deadlock.
 */
object GeneratorCreateBridge {

    /**
     * Immutable generation context, captured once per generation and
     * validated on every operation and callback. Any field mismatch
     * means stale — never coerced, never defaulted.
     */
    data class GenerationContext(
        val owner: UserId,
        val sessionEpoch: Long,
        val contentRevision: Long,
        val generationId: String,
    )

    /** Opened generation: immutable context plus its G3 session id. */
    data class BegunSession(val context: GenerationContext, val sessionId: String)

    /** Frozen validated handoff for later `startPublishing` (opaque here). */
    data class FrozenHandoff(
        val context: GenerationContext,
        val input: GeneratorExpression.GeneratorInput,
        val inputHash: String,
    )

    /** Outcome of one slot bind (no exceptions except cancellation). */
    sealed interface SlotResult {
        data class Bound(val lease: GeneratorStaging.Lease, val normalized: NormalizedPhoto) : SlotResult
        data class Stale(val reason: String) : SlotResult
        data class Duplicate(val ordinal: Int) : SlotResult
        data class Failed(val reason: String) : SlotResult
    }

    /**
     * Normalized derived bytes/dims from the SAME G4B bind that produced
     * the lease (single normalization, no second pass): the lease stages
     * ORIGINAL bytes per the G3 contract while this carries the derived
     * artifact the publisher consumes. Bytes use content equality.
     */
    data class NormalizedPhoto(
        val bytes: ByteArray,
        val widthPx: Int,
        val heightPx: Int,
    ) {
        override fun equals(other: Any?): Boolean =
            other is NormalizedPhoto &&
                bytes.contentEquals(other.bytes) &&
                widthPx == other.widthPx &&
                heightPx == other.heightPx

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + widthPx
            result = 31 * result + heightPx
            return result
        }
    }

    /** Outcome of freezing (no exceptions except cancellation). */
    sealed interface FreezeResult {
        data class Frozen(val handoff: FrozenHandoff) : FreezeResult
        data class Rejected(val reason: String) : FreezeResult
    }

    /**
     * Bridge instance. Owns the monotonic content-revision counter and
     * per-handle completion state; G3 owns sessions/bytes/time.
     *
     * `open`/`freeze` is `open` only so a test can inject a tampered frozen
     * handoff and prove the VM's exact-descriptor publish gate aborts before
     * any expression is sealed. Production never overrides it.
     */
    open class Bridge(
        private val staging: GeneratorStaging.Manager,
        private val binder: GeneratorSourceBinding.SourceBinder,
    ) {
        private var revisionCounter = 0L

        private data class BridgeRecord(
            val context: GenerationContext,
            val expected: List<ExpectedSlot>,
            val done: MutableSet<Int> = mutableSetOf(),
            val inFlight: MutableSet<Int> = mutableSetOf(),
        )

        private val lock = Any()
        private val records = mutableMapOf<String, BridgeRecord>()

        private val frozenSessions = mutableSetOf<String>()

        private data class ExpectedSlot(val contentId: String, val hash: String, val width: Int, val height: Int)

        /** Next monotonic content revision (separate from any UI generation). */
        fun nextRevision(): Long = synchronized(lock) { ++revisionCounter }

        /**
         * Begins one generation: opens the G3 session for [input] with an
         * adapter-side [sender]. Returns context plus session id, or null
         * when the input/sender is invalid (never a half-opened session).
         */
        fun begin(
            owner: UserId,
            sessionEpoch: Long,
            generationId: String,
            input: GeneratorExpression.GeneratorInput,
            sender: GeneratorStaging.SenderSnapshot?,
        ): BegunSession? {
            if (sessionEpoch < 0 || generationId.isBlank()) return null
            val revision = nextRevision()
            val opened = staging.openFor(
                input.copy(ownerId = owner.toRestString(), epoch = sessionEpoch),
                sender,
                revision,
            )
            if (opened !is GeneratorStaging.OpenResult.Opened) return null
            val context = GenerationContext(owner, sessionEpoch, revision, generationId)
            synchronized(lock) {
                records[opened.session.sessionId] = BridgeRecord(
                    context,
                    input.photos.map {
                        ExpectedSlot(it.contentId, it.contentHash, it.widthPx, it.heightPx)
                    },
                )
            }
            return BegunSession(context, opened.session.sessionId)
        }

        /**
         * Binds one authored slot: reads the source once (bounded),
         * verifies + normalizes through G4B on a one-shot memory source,
         * then stages the SAME bytes into G3. The bound result carries both
         * the G3 lease (ORIGINAL bytes) and the normalized derived
         * bytes/dims from that same single G4B bind. The stale/duplicate/order
         * gates plus an in-flight ordinal reservation are atomic under the
         * bridge lock and evaluated before any IO, so two concurrent binds
         * of one ordinal cannot both pass and at most one ever opens its
         * source; any bind failure revokes the whole session fail-closed
         * (no partial frozen handoff can form). A bind parked in IO whose
         * session dies underneath completes as [SlotResult.Stale] without
         * touching any other record. Cancellation always revokes the
         * matched session first, then rethrows.
         */
        suspend fun bindSlot(
            context: GenerationContext,
            sessionId: String,
            ordinal: Int,
            source: PhotoSource,
        ): SlotResult {
            currentCoroutineContext().ensureActive()
            val expected = synchronized(lock) {
                val record = records[sessionId] ?: return SlotResult.Stale("unknown session")
                if (context != record.context) return SlotResult.Stale("stale context")
                if (sessionId in frozenSessions) return SlotResult.Stale("already consumed")
                if (ordinal in record.done || ordinal in record.inFlight) {
                    return SlotResult.Duplicate(ordinal)
                }
                if (ordinal != record.done.size) {
                    revokeIfMatchedLocked(context, sessionId)
                    return SlotResult.Failed("out-of-order stage")
                }
                val probe = record.expected.getOrNull(ordinal)
                    ?: run {
                        revokeIfMatchedLocked(context, sessionId)
                        return SlotResult.Failed("unknown slot")
                    }
                record.inFlight += ordinal
                GeneratorSourceBinding.ExpectedOriginal(
                    probe.contentId, ordinal, probe.hash, probe.width, probe.height,
                )
            }
            val original: ByteArray = try {
                source.openInputStream().use { stream ->
                    stream.readBoundedBytes(PhotoStagingPipeline.MAX_SOURCE_BYTES)
                }
            } catch (e: CancellationException) {
                synchronized(lock) { revokeIfMatchedLocked(context, sessionId) }
                throw e
            } catch (e: IllegalArgumentException) {
                synchronized(lock) { revokeIfMatchedLocked(context, sessionId) }
                return SlotResult.Failed("source over bound")
            } catch (e: IOException) {
                synchronized(lock) { revokeIfMatchedLocked(context, sessionId) }
                return SlotResult.Failed("source unreadable")
            } catch (e: Exception) {
                synchronized(lock) { revokeIfMatchedLocked(context, sessionId) }
                return SlotResult.Failed("source failed")
            }
            val bound = try {
                binder.bindOne(PhotoSource { ByteArrayInputStream(original) }, expected)
            } catch (e: CancellationException) {
                synchronized(lock) { revokeIfMatchedLocked(context, sessionId) }
                throw e
            } catch (e: Exception) {
                synchronized(lock) { revokeIfMatchedLocked(context, sessionId) }
                return SlotResult.Failed("bind failed")
            }
            if (bound !is GeneratorSourceBinding.BindResult.Bound) {
                val reason = (bound as GeneratorSourceBinding.BindResult.Rejected).reason
                synchronized(lock) { revokeIfMatchedLocked(context, sessionId) }
                return SlotResult.Failed(reason)
            }
            synchronized(lock) {
                val record = records[sessionId] ?: return SlotResult.Stale("unknown session")
                if (context != record.context) return SlotResult.Stale("stale context")
                if (sessionId in frozenSessions) {
                    record.inFlight -= ordinal
                    return SlotResult.Stale("already consumed")
                }
                if (ordinal in record.done) {
                    record.inFlight -= ordinal
                    return SlotResult.Duplicate(ordinal)
                }
                if (ordinal !in record.inFlight) return SlotResult.Stale("stale context")
                val staged = staging.stagePhoto(
                    sessionId, context.owner.toRestString(), context.sessionEpoch,
                    context.contentRevision, ordinal,
                    bound.bound.uprightWidthPx, bound.bound.uprightHeightPx, original,
                )
                if (staged !is GeneratorStaging.StageResult.Staged) {
                    val reason = (staged as GeneratorStaging.StageResult.Rejected).reason
                    revokeIfMatchedLocked(context, sessionId)
                    return SlotResult.Failed(reason)
                }
                record.inFlight -= ordinal
                record.done += ordinal
                return SlotResult.Bound(
                    staged.lease,
                    NormalizedPhoto(
                        bound.bound.normalizedBytes,
                        bound.bound.normalizedWidthPx,
                        bound.bound.normalizedHeightPx,
                    ),
                )
            }
        }

        /**
         * Freezes a fully bound generation into an immutable handoff.
         * Never calls any publisher; the handoff is opaque data for a
         * later `startPublishing` after its own guards. One-shot: the
         * first successful freeze consumes the session, a second freeze
         * is rejected as already consumed. Fully serialized with binds
         * and invalidations on the bridge lock, so a freeze racing an
         * in-flight bind observes either the pre-bind (incomplete) or the
         * post-bind state, never a torn one.
         */
        open fun freeze(context: GenerationContext, sessionId: String): FreezeResult {
            synchronized(lock) {
                val record = records[sessionId] ?: return FreezeResult.Rejected("unknown session")
                if (context != record.context) return FreezeResult.Rejected("stale context")
                if (sessionId in frozenSessions) return FreezeResult.Rejected("already consumed")
                val input = staging.toInput(
                    sessionId, context.owner.toRestString(),
                    context.sessionEpoch, context.contentRevision,
                ) ?: return FreezeResult.Rejected("incomplete session")
                if (record.done.size != input.photos.size) return FreezeResult.Rejected("incomplete slots")
                if (GeneratorExpression.validate(input) !is GeneratorExpression.InputValidation.Valid) {
                    return FreezeResult.Rejected("invalid input")
                }
                frozenSessions += sessionId
                return FreezeResult.Frozen(
                    FrozenHandoff(context, input, GeneratorExpression.canonicalHash(input)),
                )
            }
        }

        /**
         * Photo edit: revokes the session, but only on exact context
         * match — a stale or mismatched call is a no-op that preserves
         * the live record and its G3 bytes.
         */
        fun onPhotoEdit(context: GenerationContext, sessionId: String) {
            synchronized(lock) { revokeIfMatchedLocked(context, sessionId) }
        }

        /** Note edit: revokes the session; gated exactly like [onPhotoEdit]. */
        fun onNoteEdit(context: GenerationContext, sessionId: String) {
            synchronized(lock) { revokeIfMatchedLocked(context, sessionId) }
        }

        /**
         * Owner switch or epoch change: revokes; caller begins fresh.
         * Callers pass the session's OWN context; a mismatched context
         * is a no-op so a live record is never dropped by mistake.
         */
        fun onOwnerOrEpochChange(context: GenerationContext, sessionId: String) {
            synchronized(lock) { revokeIfMatchedLocked(context, sessionId) }
        }

        /**
         * Cancel: revokes staging (cleanup via G3) and drops the record;
         * gated exactly like [onPhotoEdit] so a stale cancel cannot kill
         * a live session.
         */
        fun cancel(context: GenerationContext, sessionId: String) {
            synchronized(lock) { revokeIfMatchedLocked(context, sessionId) }
        }

        /** Logout: revokes every session of the owner held by this bridge. */
        fun onLogout(owner: UserId) {
            synchronized(lock) {
                staging.revokeOwner(owner.toRestString())
                val owned = records.filterValues { it.context.owner == owner }.keys.toList()
                for (id in owned) {
                    records.remove(id)
                    frozenSessions.remove(id)
                }
            }
        }

        /**
         * Revokes the G3 session and drops the bridge record, but ONLY
         * when the stored record still carries exactly [context]. A
         * missing record or a context mismatch is a no-op: the live
         * record (possibly a newer session under the same id) and its G3
         * bytes are preserved. Must be called with [lock] held; the G3
         * revoke underneath is synchronous and short. Returns true when
         * a matched session was revoked.
         */
        private fun revokeIfMatchedLocked(context: GenerationContext, sessionId: String): Boolean {
            val record = records[sessionId] ?: return false
            if (record.context != context) return false
            revokeQuietly(context, sessionId)
            records.remove(sessionId)
            frozenSessions.remove(sessionId)
            return true
        }

        private fun revokeQuietly(context: GenerationContext, sessionId: String) {
            try {
                staging.revoke(
                    sessionId, context.owner.toRestString(),
                    context.sessionEpoch, context.contentRevision,
                )
            } catch (_: Exception) {
            }
        }
    }
}
