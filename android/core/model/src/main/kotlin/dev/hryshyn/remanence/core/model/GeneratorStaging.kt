package dev.hryshyn.remanence.core.model

/**
 * G3 — provider-independent source staging leases and
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
 * Scope (strict): ORIGINAL-byte staging and hash verification
 * (hash + declared dims + order binding), leases, sessions, epoch/revision gating, revocation/close/sweep,
 * hash-keyed reuse within policy, TOCTOU defense, deterministic G1
 * bridge. Explicitly NOT in G3: Android content-URI adapter (G4), real
 * renderer algorithms, crop analysis/CV, UI, publishing, receive
 * presentation, encryption transport, M4.
 *
 * Ruling applied: leases contain ORIGINAL source bytes bounded by the
 * raw-source limit ([RAW_SOURCE_MAX_BYTES], same value as
 * `PhotoStagingPipeline.MAX_SOURCE_BYTES`: read bounded, normalize
 * separately); the staged hash must equal the G1 `contentHash`.
 * Normalization and decoded-dimension verification are derived G4
 * operations and are NOT claimed here: staged dimensions are
 * caller-declared metadata (positivity-bounded, never verified against
 * decoded bytes), while identity always rests on bytes→hash binding.
 * Client-owned read handles and sender representation live
 * adapter-/staging-side ONLY and never enter `GeneratorInput`
 * canonical bytes/hash (G1 uses stable IDs, authored order, dimensions
 * and content hashes only). Original bytes never enter identity either:
 * only their SHA-256 does.
 *
 * Threading: every public [Manager] method is serialized on an internal
 * lock; callers need no external synchronization. Time comes from the
 * injected `nowMillis`, which the adapter MUST drive with a monotonic
 * clock (e.g. `elapsedRealtime`): a rolled-back clock (`now < lastUse`)
 * naturally disables sweeping (`now - lastUse > ttl` is false), which
 * is the documented fail-closed direction — sessions are kept, never
 * wrongly expired. Rollback rule: store writes happen BEFORE in-memory
 * staged entries, so a throwing store leaves nothing to compensate;
 * deletes are per-key best-effort and never abort siblings.
 */
object GeneratorStaging {

    /** Max concurrently active sessions (DoS ceiling). */
    const val MAX_SESSIONS = 16

    /** Max staged leases per session (photos are 3..5; margin for restage). */
    const val MAX_LEASES_PER_SESSION = 8

    /** Max total staged bytes per session. */
    const val MAX_SESSION_BYTES = 67_108_864L

    /**
     * Max ORIGINAL source bytes per lease (raw-source limit, same value
     * as `PhotoStagingPipeline.MAX_SOURCE_BYTES`: bounded read first,
     * normalization separately in G4).
     */
    const val RAW_SOURCE_MAX_BYTES = 33_554_432

    /** Max chars for handle/session/sender ids. */
    const val MAX_HANDLE_CHARS = 256

    /** Max chars for a content ID (G1 sets no ceiling; the boundary does). */
    const val MAX_CONTENT_ID_CHARS = 128

    /** Max hash-keyed analysis entries. */
    const val MAX_ANALYSES = 256

    /** Max chars per analysis value. */
    const val MAX_ANALYSIS_CHARS = 4096

    /** Default session TTL in milliseconds (30 minutes). */
    const val DEFAULT_SESSION_TTL_MILLIS = 30L * 60L * 1000L

    /** Session lifecycle state. */
    enum class SessionState { ACTIVE, CANCELLED, CLOSED }

    /**
     * Adapter-side sender representation: opaque public-only strings.
     * Stored with the session, exposed for publish, NEVER part of G1
     * identity. `toString` is redacted so logs cannot leak either value.
     */
    data class SenderSnapshot(val senderId: String, val displayLabel: String) {
        override fun toString(): String = "SenderSnapshot(<redacted>)"
    }

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

    /**
     * Minimal byte store; production provides the owned-staging backend.
     *
     * Recovery contract (proven in G4, specified here): the production
     * backend MUST be reconstructible from an owner/session manifest or
     * support authenticated owner/session key enumeration for orphan
     * cleanup. Unknown or foreign-owner keys are NEVER swept or deleted
     * cross-owner: they are left alone and reported. The in-memory fake
     * namespaces every key by session, so unknown keys are impossible by
     * construction there.
     */
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
        private val lock = Any()

        private data class SessionRecord(
            var session: StagingSession,
            val expected: List<GeneratorExpression.PhotoRef>,
            val note: String?,
            val music: GeneratorExpression.MusicRef?,
            val ttlMillis: Long,
            val staged: MutableMap<Int, StagedBytes> = mutableMapOf(),
            var lastUseAt: Long = 0L,
        )

        private data class StagedBytes(
            val bytes: ByteArray,
            val widthPx: Int,
            val heightPx: Int,
        )

        private val sessions = mutableMapOf<String, SessionRecord>()
        private val analyses = LinkedHashMap<String, String>()

        private fun sessionIdFor(ownerId: String, epoch: Long, revision: Long, inputHash: String): String =
            "stg-" + GeneratorExpression.sha256Hex(
                "$ownerId|$epoch|$revision|$inputHash".toByteArray(Charsets.UTF_8),
            ).take(16)

        private fun keyFor(sessionId: String, ordinal: Int) = "$sessionId/$ordinal"

        private fun canonicalOwner(raw: String): String? = try {
            UserId.parseRest(raw).toRestString()
        } catch (e: IllegalArgumentException) {
            null
        }

        /**
         * Opens (or idempotently rejoins) the session for one validated
         * G1 input. Sender snapshot is attached adapter-side only. A
         * conflicting sender on rejoin is rejected, never silently kept.
         * TTL must be positive and is stored as the session's expiry
         * policy; exact boundary: expiry iff `now - lastUse > ttl`.
         */
        fun openFor(
            input: GeneratorExpression.GeneratorInput,
            sender: SenderSnapshot?,
            contentRevision: Long = 0L,
            ttlMillis: Long = DEFAULT_SESSION_TTL_MILLIS,
        ): OpenResult = synchronized(lock) {
            if (ttlMillis <= 0) return OpenResult.Rejected("bad ttl")
            if (sender != null) {
                if (sender.senderId.isBlank() || sender.senderId.length > MAX_HANDLE_CHARS) {
                    return OpenResult.Rejected("bad sender")
                }
                if (sender.displayLabel.isBlank() || sender.displayLabel.length > MAX_HANDLE_CHARS) {
                    return OpenResult.Rejected("bad sender")
                }
            }
            val owner = canonicalOwner(input.ownerId) ?: return OpenResult.Rejected("non-canonical owner")
            if (GeneratorExpression.validate(input) !is GeneratorExpression.InputValidation.Valid) {
                return OpenResult.Rejected("invalid input")
            }
            if (input.photos.any { it.contentId.length > MAX_CONTENT_ID_CHARS }) {
                return OpenResult.Rejected("oversized content id")
            }
            if (contentRevision < 0) return OpenResult.Rejected("bad revision")
            val now = nowMillis()
            val id = sessionIdFor(
                owner, input.epoch, contentRevision,
                GeneratorExpression.canonicalHash(input),
            )
            sessions[id]?.let { record ->
                if (record.session.state != SessionState.ACTIVE) {
                    sessions.remove(id)
                } else {
                    if (sender != null && sender != record.session.sender) {
                        return OpenResult.Rejected("sender conflict")
                    }
                    record.lastUseAt = now
                    return OpenResult.Opened(record.session)
                }
            }
            val active = sessions.values.count { it.session.state == SessionState.ACTIVE }
            if (active >= MAX_SESSIONS) return OpenResult.Rejected("too many sessions")
            val session = StagingSession(
                sessionId = id,
                ownerId = owner,
                epoch = input.epoch,
                contentRevision = contentRevision,
                photoIds = input.photos.map { it.contentId },
                sender = sender,
                state = SessionState.ACTIVE,
            )
            sessions[id] = SessionRecord(
                session, input.photos.toList(), input.note, input.music, ttlMillis, lastUseAt = now,
            )
            return OpenResult.Opened(session)
        }

        /**
         * Stages one ORIGINAL source ([RAW_SOURCE_MAX_BYTES] bound) at
         * exactly the next authored ordinal. The staged hash must equal
         * the G1 `contentHash`; staged dimensions are caller-declared
         * metadata (positivity-bounded, NOT verified against decoded
         * bytes — that verification is G4). Store write precedes the
         * in-memory entry, so a throwing store needs no compensation.
         */
        fun stagePhoto(
            sessionId: String,
            ownerId: String,
            epoch: Long,
            revision: Long,
            ordinal: Int,
            widthPx: Int,
            heightPx: Int,
            bytes: ByteArray,
        ): StageResult = synchronized(lock) {
            val record = sessions[sessionId] ?: return StageResult.Rejected("unknown session")
            gate(record, ownerId, epoch, revision)?.let { return StageResult.Rejected(it) }
            if (ordinal != record.staged.size) return StageResult.Rejected("out-of-order stage")
            if (ordinal >= record.expected.size) return StageResult.Rejected("unknown slot")
            val expected = record.expected[ordinal]
            if (widthPx <= 0 || heightPx <= 0) return StageResult.Rejected("non-positive dims")
            if (bytes.isEmpty()) return StageResult.Rejected("empty bytes")
            if (bytes.size > RAW_SOURCE_MAX_BYTES) return StageResult.Rejected("bytes over bound")
            if (record.staged.size >= MAX_LEASES_PER_SESSION) return StageResult.Rejected("too many leases")
            val sessionBytes = record.staged.values.sumOf { it.bytes.size.toLong() } + bytes.size
            if (sessionBytes > MAX_SESSION_BYTES) return StageResult.Rejected("session bytes over bound")
            val hash = GeneratorExpression.sha256Hex(bytes)
            if (hash != expected.contentHash) return StageResult.Rejected("hash mismatch at stage")
            try {
                store.put(keyFor(sessionId, ordinal), bytes.copyOf())
            } catch (e: Exception) {
                return StageResult.Rejected("store unavailable")
            }
            record.staged[ordinal] = StagedBytes(bytes.copyOf(), widthPx, heightPx)
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
         * Reads staged bytes back through the issued [lease] capability:
         * every lease field is validated against the active record, then
         * the stored bytes are re-hashed (TOCTOU defense).
         */
        fun use(lease: Lease, ownerId: String, epoch: Long, revision: Long): LeaseUse = synchronized(lock) {
            if (lease.handleId != "${lease.sessionId}#${lease.ordinal}") {
                return LeaseUse.Rejected("forged lease")
            }
            val record = sessions[lease.sessionId] ?: return LeaseUse.Rejected("unknown session")
            gate(record, ownerId, epoch, revision)?.let { return LeaseUse.Rejected(it) }
            if (lease.ordinal < 0 || lease.ordinal >= record.expected.size) {
                return LeaseUse.Rejected("unknown slot")
            }
            val expected = record.expected[lease.ordinal]
            if (lease.contentId != expected.contentId || lease.contentHash != expected.contentHash) {
                return LeaseUse.Rejected("wrong content")
            }
            val staged = record.staged[lease.ordinal] ?: return LeaseUse.Rejected("not staged")
            if (lease.byteCount != staged.bytes.size.toLong()) return LeaseUse.Rejected("byte count mismatch")
            val stored = try {
                store.get(keyFor(lease.sessionId, lease.ordinal))
            } catch (e: Exception) {
                return LeaseUse.Rejected("store unavailable")
            } ?: return LeaseUse.Rejected("store miss")
            if (GeneratorExpression.sha256Hex(stored) != expected.contentHash) {
                return LeaseUse.Rejected("hash mismatch at use")
            }
            if (!stored.contentEquals(staged.bytes)) return LeaseUse.Rejected("store divergence")
            record.lastUseAt = nowMillis()
            return LeaseUse.Bytes(stored.copyOf())
        }

        /** Rebuilds the deterministic G1 input from staged slots + policy. */
        fun toInput(sessionId: String, ownerId: String, epoch: Long, revision: Long): GeneratorExpression.GeneratorInput? =
            synchronized(lock) {
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
                GeneratorExpression.GeneratorInput(
                    ownerId = record.session.ownerId,
                    epoch = record.session.epoch,
                    photos = photos,
                    note = record.note,
                    music = record.music,
                )
            }

        /** Adapter-side sender snapshot (never in G1 identity). */
        fun senderOf(sessionId: String, ownerId: String, epoch: Long, revision: Long): SenderSnapshot? =
            synchronized(lock) {
                val record = sessions[sessionId] ?: return null
                if (gate(record, ownerId, epoch, revision) != null) return null
                return record.session.sender
            }

        /** Idempotent revoke: CANCELLED + staged bytes deleted + row evicted. */
        fun revoke(sessionId: String, ownerId: String, epoch: Long, revision: Long): Int = synchronized(lock) {
            val record = sessions[sessionId] ?: return 0
            if (gate(record, ownerId, epoch, revision) != null) return 0
            val scope = Triple(record.session.ownerId, record.session.epoch, record.session.contentRevision)
            record.session = record.session.copy(state = SessionState.CANCELLED)
            val deleted = deleteStaged(record, sessionId)
            sessions.remove(sessionId)
            dropScope(scope.first, scope.second, scope.third)
            return deleted
        }

        /** Idempotent close: CLOSED + staged bytes deleted + row evicted. */
        fun close(sessionId: String, ownerId: String, epoch: Long, revision: Long): Int = synchronized(lock) {
            val record = sessions[sessionId] ?: return 0
            if (gate(record, ownerId, epoch, revision) != null) return 0
            val scope = Triple(record.session.ownerId, record.session.epoch, record.session.contentRevision)
            record.session = record.session.copy(state = SessionState.CLOSED)
            val deleted = deleteStaged(record, sessionId)
            sessions.remove(sessionId)
            dropScope(scope.first, scope.second, scope.third)
            return deleted
        }

        /** Revokes every session of one owner (logout / rotation) + clears scope. */
        fun revokeOwner(ownerId: String): Int = synchronized(lock) {
            val owner = canonicalOwner(ownerId) ?: return 0
            var deleted = 0
            for ((id, record) in sessions.toList()) {
                if (record.session.ownerId == owner && record.session.state == SessionState.ACTIVE) {
                    record.session = record.session.copy(state = SessionState.CANCELLED)
                    deleted += deleteStaged(record, id)
                    sessions.remove(id)
                }
            }
            val prefix = "|$owner|"
            for (key in analyses.keys.toList()) {
                if (key.contains(prefix)) analyses.remove(key)
            }
            return deleted
        }

        /**
         * Sweeps sessions idle longer than their STORED policy
         * (`now - lastUse > ttl`; exact `now == lastUse + ttl` stays live).
         * Evicts swept rows and drops their analysis scope.
         */
        fun sweep(now: Long): Int = synchronized(lock) {
            var swept = 0
            for ((id, record) in sessions.toList()) {
                if (record.session.state == SessionState.ACTIVE && now - record.lastUseAt > record.ttlMillis) {
                    record.session = record.session.copy(state = SessionState.CLOSED)
                    deleteStaged(record, id)
                    sessions.remove(id)
                    dropScope(record.session.ownerId, record.session.epoch, record.session.contentRevision)
                    swept++
                }
            }
            return swept
        }

        /**
         * Hash-keyed analysis reuse within policy ONLY: same content hash,
         * owner, epoch and revision hit; anything else misses. Over-length
         * values are refused; beyond [MAX_ANALYSES] the eldest entry is
         * evicted (insertion order — deterministic).
         */
        fun rememberAnalysis(
            contentHash: String,
            ownerId: String,
            epoch: Long,
            revision: Long,
            value: String,
        ): Boolean = synchronized(lock) {
            if (value.length > MAX_ANALYSIS_CHARS) return false
            val owner = canonicalOwner(ownerId) ?: return false
            val key = "$contentHash|$owner|$epoch|$revision"
            analyses.remove(key)
            analyses[key] = value
            while (analyses.size > MAX_ANALYSES) {
                analyses.remove(analyses.keys.first())
            }
            return true
        }

        fun lookupAnalysis(contentHash: String, ownerId: String, epoch: Long, revision: Long): String? =
            synchronized(lock) {
                val owner = canonicalOwner(ownerId) ?: return null
                return analyses["$contentHash|$owner|$epoch|$revision"]
            }

        /** For tests/introspection: current live session count. */
        internal fun liveSessionCount(): Int = synchronized(lock) {
            sessions.values.count { it.session.state == SessionState.ACTIVE }
        }

        private fun gate(record: SessionRecord, ownerId: String, epoch: Long, revision: Long): String? {
            if (record.session.state != SessionState.ACTIVE) return "session not active"
            val owner = canonicalOwner(ownerId) ?: return "non-canonical owner"
            if (record.session.ownerId != owner) return "owner mismatch"
            if (record.session.epoch != epoch) return "stale epoch"
            if (record.session.contentRevision != revision) return "stale revision"
            if (nowMillis() - record.lastUseAt > record.ttlMillis) return "expired"
            return null
        }

        private fun deleteStaged(record: SessionRecord, sessionId: String): Int {
            var deleted = 0
            for (ordinal in record.staged.keys.toList()) {
                try {
                    if (store.delete(keyFor(sessionId, ordinal))) deleted++
                } catch (e: Exception) {
                    continue
                }
            }
            record.staged.clear()
            return deleted
        }

        private fun dropScope(ownerId: String, epoch: Long, revision: Long) {
            for (key in analyses.keys.toList()) {
                val parts = key.split("|")
                if (parts.size == 4 && parts[1] == ownerId && parts[2] == epoch.toString() &&
                    parts[3] == revision.toString()
                ) {
                    analyses.remove(key)
                }
            }
        }
    }
}
