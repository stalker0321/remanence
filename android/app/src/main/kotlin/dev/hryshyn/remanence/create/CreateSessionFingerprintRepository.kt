package dev.hryshyn.remanence.create

import java.util.UUID
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.fingerprints.DuplicateFingerprintException
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence

/** Capture-only side state; it must never cross into Room persistence. */
enum class CaptureFingerprintSide { FRONT, BACK }

/** One side's serialized fingerprint handed off by the capture pipeline. */
class StagedSideFingerprint(
    val profileId: String,
    val side: CaptureFingerprintSide,
    /** Caller hands this buffer to the repository, which wipes it on return. */
    val serializedBytes: ByteArray,
)

/**
 * Extraction port over :core:recognition so this repository stays free of
 * OpenCV and bitmap types; the real adapter runs the bounded capture
 * pipeline plus ORB extraction at wiring time.
 */
fun interface SideFingerprintExtractor {
    fun extract(side: CaptureFingerprintSide): StagedSideFingerprint
}

/**
 * Session-local handoff for the deferred two-sided Create flow. BACK material
 * is deliberately never represented by a Room row; it remains in memory until
 * the current session consumes or clears it.
 */
class CreateCaptureSessionStore internal constructor(
    /** Internal test observer; zeroization below is always authoritative. */
    private val wipeObserver: ((ByteArray) -> Unit)? = null,
    /** Internal test observer for the copy transferred by [take]. */
    private val takeObserver: ((ByteArray) -> Unit)? = null,
) {
    private val values = LinkedHashMap<String, ByteArray>()

    private fun wipe(bytes: ByteArray) {
        bytes.fill(0)
        runCatching { wipeObserver?.invoke(bytes) }
    }

    private fun observeTaken(bytes: ByteArray) {
        runCatching { takeObserver?.invoke(bytes) }
    }

    /**
     * Stores a private copy. The caller retains ownership of [bytes] and must
     * wipe that handoff after this call.
     */
    @Synchronized
    fun stage(bytes: ByteArray): String {
        require(bytes.isNotEmpty()) { "fingerprint bytes are empty" }
        val id = "capture-${UUID.randomUUID()}"
        values[id] = bytes.copyOf()
        return id
    }

    /**
     * Removes one staged value and transfers a private copy to the caller.
     * The caller owns the returned copy and must wipe it after use; the
     * store's internal copy is wiped before this method returns.
     */
    @Synchronized
    fun take(id: String): ByteArray? {
        val stored = values.remove(id) ?: return null
        val transferred = try {
            stored.copyOf()
        } finally {
            // Even an exceptional copy must not leave the removed value live.
            wipe(stored)
        }
        observeTaken(transferred)
        return transferred
    }

    @Synchronized
    fun clear() {
        values.values.forEach(::wipe)
        values.clear()
    }
}

/**
 * Create-session capture persistence (docs/implementation-plan.md M1-R17):
 * captures the postcard FRONT first, sealing the fingerprint before it ever
 * touches disk, and refuses a second front for the same capsule instead of
 * silently replacing the baseline.
 */
class CreateSessionFingerprintRepository(
    private val persistence: SealedFingerprintPersistence,
    private val extractor: SideFingerprintExtractor,
    private val captureStore: CreateCaptureSessionStore = CreateCaptureSessionStore(),
) {

    /**
     * Extracts and persists the sender FRONT baseline for [capsuleId];
     * returns the new fingerprint ID.
     */
    suspend fun captureFront(capsuleId: String): String {
        requireValidCapsuleId(capsuleId)
        val staged = extractor.extract(CaptureFingerprintSide.FRONT)
        return try {
            require(staged.side == CaptureFingerprintSide.FRONT) { "extractor returned wrong side" }
            persistence.persist(
                capsuleId = capsuleId,
                origin = FingerprintOrigin.SENDER,
                profileId = staged.profileId,
                plaintextBytes = staged.serializedBytes,
            )
        } catch (duplicate: DuplicateFingerprintException) {
            throw IllegalStateException("front baseline already captured for this capsule", duplicate)
        } finally {
            staged.serializedBytes.fill(0)
        }
    }

    /** Extracts the sender BACK baseline into the live capture session only. */
    suspend fun captureBack(capsuleId: String): String {
        requireValidCapsuleId(capsuleId)
        if (!persistence.hasBaseline(capsuleId, FingerprintOrigin.SENDER)) {
            throw IllegalStateException("front must be captured before the back")
        }
        val staged = extractor.extract(CaptureFingerprintSide.BACK)
        return try {
            require(staged.side == CaptureFingerprintSide.BACK) { "extractor returned wrong side" }
            captureStore.stage(staged.serializedBytes)
        } finally {
            staged.serializedBytes.fill(0)
        }
    }

    private fun requireValidCapsuleId(capsuleId: String) {
        try {
            UUID.fromString(capsuleId)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("capsule id must be a canonical UUID string")
        }
        require(capsuleId == capsuleId.lowercase()) { "capsule id must be lowercase" }
    }
}
