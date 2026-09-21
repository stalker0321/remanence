package dev.hryshyn.remanence.core.model

/**
 * G3 — provider-independent source normalization/staging leases and
 * invalidation semantics bridging client-owned read handles to stable
 * G1 input IDs/hashes.
 *
 * Authority: `docs/hold/generator-boundary.md` (bounded read handles;
 * no plaintext generated previews in durable storage outside the owned
 * staging lifecycle; explicit cleanup; selection/crop/freeze
 * invalidation on photo/note edits, owner change, cancellation and
 * process death), arch §2/§8 (freeze content references; reusable
 * per-original analysis), and the G2-FINAL-VERDICT G3 checklist
 * (exact once-and-in-order mapping; owner/epoch/revision on every
 * use/callback; frozen publish snapshot; handles/paths/plaintext and
 * sender representation outside G1 bytes/hash; cleanup on cancel,
 * failure, revocation, death, handoff).
 *
 * Scope (strict): normalization (hash + dims + order binding), leases,
 * sessions, epoch/revision gating, revocation/close/sweep, hash-keyed
 * reuse within policy, TOCTOU defense, deterministic G1/G2 bridge.
 * Explicitly NOT in G3: Android content-URI adapter (G4), real renderer
 * algorithms, crop analysis/CV, UI, publishing, receive presentation,
 * encryption transport, M4. Storage is behind [BlobStore]; tests use an
 * in-memory fake only.
 *
 * Ruling applied: client-owned read handles and sender representation
 * live adapter-/staging-side ONLY and never enter `GeneratorInput`
 * canonical bytes/hash (G1 uses stable IDs, authored order, dimensions
 * and content hashes only). Original bytes never enter identity either:
 * only their SHA-256 does.
 */
object GeneratorStaging {

    /** Max concurrently active sessions (DoS ceiling). */
    const val MAX_SESSIONS = 16

    /** Max staged leases per session (photos are 3..5; margin for restage). */
    const val MAX_LEASES_PER_SESSION = 8

    /** Max total staged bytes per session. */
    const val MAX_SESSION_BYTES = 67_108_864L

    /** Max chars for handle/session/sender ids. */
    const val MAX_HANDLE_CHARS = 256

    /** Default session TTL in milliseconds (30 minutes). */
    const val DEFAULT_SESSION_TTL_MILLIS = 30L * 60L * 1000L

    /** Session lifecycle state. */
    enum class SessionState { ACTIVE, CANCELLED, CLOSED }

    /**
     * Adapter-side sender representation (opaque strings). Stored with
     * the session, exposed for publish, NEVER part of G1 identity.
     */
    data class SenderSnapshot(val senderId: String, val displayLabel: String)

    /** One staged original bound to an authored photo slot. */
    data class Lease(
        val handleId: String,
        val sessionId: String,
        val contentId: String,
        val ordinal: Int,
        val contentHash: String,
        val byteCount: Long,
    )

    /** Session bound to one G1 input's authored photo order. */
    data class StagingSession(
        val sessionId: String,
        val ownerId: String,
        val epoch: Long,
        val contentRevision: Long,
        val photoIds: List<String>,
        val sender: SenderSnapshot?,
        val state: SessionState,
    )

    /** Outcome of staging one original. */
    sealed interface StageResult {
        data class Staged(val lease: Lease) : StageResult
        data class Rejected(val reason: String) : StageResult
    }

    /** Outcome of reading staged bytes back (hash verified at use). */
    sealed interface LeaseUse {
        data class Bytes(val bytes: ByteArray) : LeaseUse {
            override fun equals(other: Any?): Boolean =
                other is Bytes && bytes.contentEquals(other.bytes)
            override fun hashCode(): Int = bytes.contentHashCode()
        }
        data class Rejected(val reason: String) : LeaseUse
    }

    /** Outcome of opening (or rejoining) a session. */
    sealed interface OpenResult {
        data class Opened(val session: StagingSession) : OpenResult
        data class Rejected(val reason: String) : OpenResult
    }

    /** Minimal byte store; production provides the owned-staging backend. */
    interface BlobStore {
        fun put(key: String, bytes: ByteArray)
        fun get(key: String): ByteArray?
        fun delete(key: String): Boolean
        fun keys(): Set<String>
    }

    /**
     * Session manager. All time comes from [nowMillis] (tests pin it);
     * all bytes live in [store] (tests use an in-memory fake).
     */
    class Manager(
        private val store: BlobStore,
        private val nowMillis: () -> Long,
    ) {
        private data class SessionRecord(
            var session: StagingSession,
            val expected: List<GeneratorExpression.PhotoRef>,
            val note: String?,
            val music: GeneratorExpression.MusicRef?,
            val staged: MutableMap<Int, StagedBytes> = mutableMapOf(),
            var lastUseAt: Long = 0L,
        )

        private data class StagedBytes(
            val bytes: ByteArray,
            val widthPx: Int,
            val heightPx: Int,
        )

        private val sessions = mutableMapOf<String, SessionRecord>()
        private val analyses = mutableMapOf<String, String>()

        private fun sessionIdFor(ownerId: String, epoch: Long, revision: Long, inputHash: String): String =
            "stg-" + GeneratorExpression.sha256Hex(
                "$ownerId|$epoch|$revision|$inputHash".toByteArray(Charsets.UTF_8),
            ).take(16)

        private fun keyFor(sessionId: String, ordinal: Int) = "$sessionId/$ordinal"

        /**
         * Opens (or idempotently rejoins) the session for one validated
         * G1 input. Sender snapshot is attached adapter-side only.
         */
        fun openFor(
            input: GeneratorExpression.GeneratorInput,
            sender: SenderSnapshot?,
            contentRevision: Long = 0L,
            ttlMillis: Long = DEFAULT_SESSION_TTL_MILLIS,
        ): OpenResult {
            if (sender != null) {
                if (sender.senderId.isBlank() || sender.senderId.length > MAX_HANDLE_CHARS) {
                    return OpenResult.Rejected("bad sender")
                }
            }
            if (input.ownerId.isBlank() || input.ownerId.length > MAX_HANDLE_CHARS) {
                return OpenResult.Rejected("bad owner")
            }
            if (GeneratorExpression.validate(input) !is GeneratorExpression.InputValidation.Valid) {
                return OpenResult.Rejected("invalid input")
            }
            if (contentRevision < 0) return OpenResult.Rejected("bad revision")
            val now = nowMillis()
            val id = sessionIdFor(
                input.ownerId, input.epoch, contentRevision,
                GeneratorExpression.canonicalHash(input),
            )
            sessions[id]?.let { record ->
                if (record.session.state == SessionState.ACTIVE) {
                    record.lastUseAt = now
                    return OpenResult.Opened(record.session)
                }
                return OpenResult.Rejected("session not active")
            }
            val active = sessions.values.count { it.session.state == SessionState.ACTIVE }
            if (active >= MAX_SESSIONS) return OpenResult.Rejected("too many sessions")
            val session = StagingSession(
                sessionId = id,
                ownerId = input.ownerId,
                epoch = input.epoch,
                contentRevision = contentRevision,
                photoIds = input.photos.map { it.contentId },
                sender = sender,
                state = SessionState.ACTIVE,
            )
            sessions[id] = SessionRecord(session, input.photos.toList(), input.note, input.music, lastUseAt = now)
            return OpenResult.Opened(session)
        }

        /** Stages one original at exactly the next authored ordinal. */
        fun stagePhoto(
            sessionId: String,
            ownerId: String,
            epoch: Long,
            revision: Long,
            ordinal: Int,
            widthPx: Int,
            heightPx: Int,
            bytes: ByteArray,
        ): StageResult {
            val record = sessions[sessionId] ?: return StageResult.Rejected("unknown session")
            gate(record, ownerId, epoch, revision)?.let { return StageResult.Rejected(it) }
            if (ordinal != record.staged.size) return StageResult.Rejected("out-of-order stage")
            if (ordinal >= record.expected.size) return StageResult.Rejected("unknown slot")
            val expected = record.expected[ordinal]
            if (widthPx != expected.widthPx || heightPx != expected.heightPx) {
                return StageResult.Rejected("dims mismatch")
            }
            if (bytes.size > ProtocolV1Limits.NORMALIZED_PHOTO_MAX_PLAINTEXT_BYTES) {
                return StageResult.Rejected("bytes over bound")
            }
            if (record.staged.size >= MAX_LEASES_PER_SESSION) return StageResult.Rejected("too many leases")
            val sessionBytes = record.staged.values.sumOf { it.bytes.size.toLong() } + bytes.size
            if (sessionBytes > MAX_SESSION_BYTES) return StageResult.Rejected("session bytes over bound")
            val hash = GeneratorExpression.sha256Hex(bytes)
            if (hash != expected.contentHash) return StageResult.Rejected("hash mismatch at stage")
            record.staged[ordinal] = StagedBytes(bytes.copyOf(), widthPx, heightPx)
            store.put(keyFor(sessionId, ordinal), bytes.copyOf())
            record.lastUseAt = nowMillis()
            return StageResult.Staged(
                Lease(
                    handleId = "$sessionId#$ordinal",
                    sessionId = sessionId,
                    contentId = expected.contentId,
                    ordinal = ordinal,
                    contentHash = hash,
                    byteCount = bytes.size.toLong(),
                ),
            )
        }

        /**
         * Reads staged bytes back with hash-at-use re-verification
         * (TOCTOU defense): the stored bytes are re-hashed on every use.
         */
        fun use(sessionId: String, ownerId: String, epoch: Long, revision: Long, ordinal: Int): LeaseUse {
            val record = sessions[sessionId] ?: return LeaseUse.Rejected("unknown session")
            gate(record, ownerId, epoch, revision)?.let { return LeaseUse.Rejected(it) }
            val staged = record.staged[ordinal] ?: return LeaseUse.Rejected("not staged")
            val stored = store.get(keyFor(sessionId, ordinal)) ?: return LeaseUse.Rejected("store miss")
            if (GeneratorExpression.sha256Hex(stored) != record.expected[ordinal].contentHash) {
                return LeaseUse.Rejected("hash mismatch at use")
            }
            if (!stored.contentEquals(staged.bytes)) return LeaseUse.Rejected("store divergence")
            record.lastUseAt = nowMillis()
            return LeaseUse.Bytes(stored.copyOf())
        }

        /** Rebuilds the deterministic G1 input from staged slots + policy. */
        fun toInput(sessionId: String, ownerId: String, epoch: Long, revision: Long): GeneratorExpression.GeneratorInput? {
            val record = sessions[sessionId] ?: return null
            if (gate(record, ownerId, epoch, revision) != null) return null
            if (record.staged.size != record.expected.size) return null
            val photos = record.expected.mapIndexed { index, expected ->
                val staged = record.staged[index] ?: return null
                GeneratorExpression.PhotoRef(
                    contentId = expected.contentId,
                    ordinal = index,
                    widthPx = staged.widthPx,
                    heightPx = staged.heightPx,
                    contentHash = expected.contentHash,
                )
            }
            return GeneratorExpression.GeneratorInput(
                ownerId = record.session.ownerId,
                epoch = record.session.epoch,
                photos = photos,
                note = record.note,
                music = record.music,
            )
        }

        /** Adapter-side sender snapshot (never in G1 identity). */
        fun senderOf(sessionId: String, ownerId: String, epoch: Long, revision: Long): SenderSnapshot? {
            val record = sessions[sessionId] ?: return null
            if (gate(record, ownerId, epoch, revision) != null) return null
            return record.session.sender
        }

        /** Idempotent revoke: state → CANCELLED + staged bytes deleted. */
        fun revoke(sessionId: String, ownerId: String, epoch: Long, revision: Long): Int {
            val record = sessions[sessionId] ?: return 0
            if (gate(record, ownerId, epoch, revision) != null) return 0
            record.session = record.session.copy(state = SessionState.CANCELLED)
            return deleteStaged(record, sessionId)
        }

        /** Idempotent close: state → CLOSED + staged bytes deleted. */
        fun close(sessionId: String, ownerId: String, epoch: Long, revision: Long): Int {
            val record = sessions[sessionId] ?: return 0
            if (gate(record, ownerId, epoch, revision) != null) return 0
            record.session = record.session.copy(state = SessionState.CLOSED)
            return deleteStaged(record, sessionId)
        }

        /** Revokes every session of one owner (logout / rotation). */
        fun revokeOwner(ownerId: String): Int {
            var deleted = 0
            for ((id, record) in sessions) {
                if (record.session.ownerId == ownerId && record.session.state == SessionState.ACTIVE) {
                    record.session = record.session.copy(state = SessionState.CANCELLED)
                    deleted += deleteStaged(record, id)
                }
            }
            return deleted
        }

        /**
         * Sweeps sessions idle longer than [ttlMillis] (process-restart /
         * TTL recovery path): closes them and deletes staged bytes.
         */
        fun sweep(now: Long, ttlMillis: Long): Int {
            var swept = 0
            for ((id, record) in sessions) {
                if (record.session.state == SessionState.ACTIVE && now - record.lastUseAt > ttlMillis) {
                    record.session = record.session.copy(state = SessionState.CLOSED)
                    deleteStaged(record, id)
                    swept++
                }
            }
            return swept
        }

        /**
         * Hash-keyed analysis reuse within policy ONLY: same content hash,
         * owner, epoch and revision hit; anything else misses (never
         * shared across owners/epochs/revisions).
         */
        fun rememberAnalysis(contentHash: String, ownerId: String, epoch: Long, revision: Long, value: String) {
            analyses["$contentHash|$ownerId|$epoch|$revision"] = value
        }

        fun lookupAnalysis(contentHash: String, ownerId: String, epoch: Long, revision: Long): String? =
            analyses["$contentHash|$ownerId|$epoch|$revision"]

        private fun gate(record: SessionRecord, ownerId: String, epoch: Long, revision: Long): String? {
            if (record.session.state != SessionState.ACTIVE) return "session not active"
            if (record.session.ownerId != ownerId) return "owner mismatch"
            if (record.session.epoch != epoch) return "stale epoch"
            if (record.session.contentRevision != revision) return "stale revision"
            return null
        }

        private fun deleteStaged(record: SessionRecord, sessionId: String): Int {
            var deleted = 0
            for (ordinal in record.staged.keys.toList()) {
                if (store.delete(keyFor(sessionId, ordinal))) deleted++
            }
            record.staged.clear()
            return deleted
        }
    }
}
