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
        data class Bound(val lease: GeneratorStaging.Lease) : SlotResult
        data class Stale(val reason: String) : SlotResult
        data class Duplicate(val ordinal: Int) : SlotResult
        data class Failed(val reason: String) : SlotResult
    }

    /** Outcome of freezing (no exceptions except cancellation). */
    sealed interface FreezeResult {
        data class Frozen(val handoff: FrozenHandoff) : FreezeResult
        data class Rejected(val reason: String) : FreezeResult
    }

    /**
     * Bridge instance. Owns the monotonic content-revision counter and
     * per-handle completion state; G3 owns sessions/bytes/time.
     */
    class Bridge(
        private val staging: GeneratorStaging.Manager,
        private val binder: GeneratorSourceBinding.SourceBinder,
    ) {
        private var revisionCounter = 0L

        private data class BridgeRecord(
            val context: GenerationContext,
            val expected: List<ExpectedSlot>,
            val done: MutableSet<Int> = mutableSetOf(),
        )

        private val records = mutableMapOf<String, BridgeRecord>()

        private val frozenSessions = mutableSetOf<String>()

        private data class ExpectedSlot(val contentId: String, val hash: String, val width: Int, val height: Int)

        /** Next monotonic content revision (separate from any UI generation). */
        fun nextRevision(): Long = ++revisionCounter

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
            records[opened.session.sessionId] = BridgeRecord(
                context,
                input.photos.map {
                    ExpectedSlot(it.contentId, it.contentHash, it.widthPx, it.heightPx)
                },
            )
            return BegunSession(context, opened.session.sessionId)
        }

        /**
         * Binds one authored slot: reads the source once (bounded),
         * verifies + normalizes through G4B on a one-shot memory source,
         * then stages the SAME bytes into G3. Context, slot freshness and
         * order are validated before any IO; any bind failure revokes the
         * whole session fail-closed (no partial frozen handoff can form).
         */
        suspend fun bindSlot(
            context: GenerationContext,
            sessionId: String,
            ordinal: Int,
            source: PhotoSource,
        ): SlotResult {
            currentCoroutineContext().ensureActive()
            val record = records[sessionId] ?: return SlotResult.Stale("unknown session")
            if (context != record.context) return SlotResult.Stale("stale context")
            if (sessionId in frozenSessions) return SlotResult.Stale("already consumed")
            if (ordinal in record.done) return SlotResult.Duplicate(ordinal)
            val probe = record.expected.getOrNull(ordinal)
                ?: return SlotResult.Failed("unknown slot").also {
                    revokeQuietly(context, sessionId)
                    kill(sessionId)
                }
            val original: ByteArray = try {
                source.openInputStream().use { stream ->
                    stream.readBoundedBytes(PhotoStagingPipeline.MAX_SOURCE_BYTES)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                revokeQuietly(context, sessionId)
                kill(sessionId)
                return SlotResult.Failed("source over bound")
            } catch (e: IOException) {
                revokeQuietly(context, sessionId)
                kill(sessionId)
                return SlotResult.Failed("source unreadable")
            } catch (e: Exception) {
                revokeQuietly(context, sessionId)
                kill(sessionId)
                return SlotResult.Failed("source failed")
            }
            val expected = GeneratorSourceBinding.ExpectedOriginal(
                probe.contentId, ordinal, probe.hash, probe.width, probe.height,
            )
            val bound = try {
                binder.bindOne(PhotoSource { ByteArrayInputStream(original) }, expected)
            } catch (e: CancellationException) {
                revokeQuietly(context, sessionId)
                kill(sessionId)
                throw e
            } catch (e: Exception) {
                revokeQuietly(context, sessionId)
                kill(sessionId)
                return SlotResult.Failed("bind failed")
            }
            if (bound !is GeneratorSourceBinding.BindResult.Bound) {
                revokeQuietly(context, sessionId)
                kill(sessionId)
                val reason = (bound as GeneratorSourceBinding.BindResult.Rejected).reason
                return SlotResult.Failed(reason)
            }
            val staged = staging.stagePhoto(
                sessionId, context.owner.toRestString(), context.sessionEpoch,
                context.contentRevision, ordinal,
                bound.bound.uprightWidthPx, bound.bound.uprightHeightPx, original,
            )
            if (staged !is GeneratorStaging.StageResult.Staged) {
                revokeQuietly(context, sessionId)
                kill(sessionId)
                val reason = (staged as GeneratorStaging.StageResult.Rejected).reason
                return SlotResult.Failed(reason)
            }
            record.done += ordinal
            return SlotResult.Bound(staged.lease)
        }

        /**
         * Freezes a fully bound generation into an immutable handoff.
         * Never calls any publisher; the handoff is opaque data for a
         * later `startPublishing` after its own guards. One-shot: the
         * first successful freeze consumes the session, a second freeze
         * is rejected as already consumed.
         */
        fun freeze(context: GenerationContext, sessionId: String): FreezeResult {
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

        /** Photo edit: revokes the session; the old context goes stale. */
        fun onPhotoEdit(context: GenerationContext, sessionId: String) {
            revokeQuietly(context, sessionId)
            kill(sessionId)
        }

        /** Note edit: revokes the session; the old context goes stale. */
        fun onNoteEdit(context: GenerationContext, sessionId: String) {
            revokeQuietly(context, sessionId)
            kill(sessionId)
        }

        /** Owner switch or epoch change: revokes; caller begins fresh. */
        fun onOwnerOrEpochChange(context: GenerationContext, sessionId: String) {
            revokeQuietly(context, sessionId)
            kill(sessionId)
        }

        /** Cancel: revokes staging (cleanup via G3) and drops the record. */
        fun cancel(context: GenerationContext, sessionId: String) {
            revokeQuietly(context, sessionId)
            kill(sessionId)
        }

        /** Logout: revokes every session of the owner held by this bridge. */
        fun onLogout(owner: UserId) {
            staging.revokeOwner(owner.toRestString())
            val owned = records.filterValues { it.context.owner == owner }.keys.toList()
            for (sessionId in owned) {
                records.remove(sessionId)
                frozenSessions.remove(sessionId)
            }
        }

        private fun kill(sessionId: String) {
            records.remove(sessionId)
            frozenSessions.remove(sessionId)
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
