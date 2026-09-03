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
class CreateCaptureSessionStore {
    private val values = LinkedHashMap<String, ByteArray>()

    @Synchronized
    fun stage(bytes: ByteArray): String {
        require(bytes.isNotEmpty()) { "fingerprint bytes are empty" }
        val id = "capture-${UUID.randomUUID()}"
        values[id] = bytes.copyOf()
        return id
    }

    @Synchronized
    fun read(id: String): ByteArray? = values[id]?.copyOf()

    @Synchronized
    fun take(id: String): ByteArray? {
        val stored = values.remove(id) ?: return null
        return stored.copyOf().also { stored.fill(0) }
    }

    @Synchronized
    fun clear() {
        values.values.forEach { it.fill(0) }
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
        require(staged.side == CaptureFingerprintSide.FRONT) { "extractor returned wrong side" }
        return try {
            persistence.persist(
                capsuleId = capsuleId,
                origin = FingerprintOrigin.SENDER,
                profileId = staged.profileId,
                plaintextBytes = staged.serializedBytes,
            )
        } catch (duplicate: DuplicateFingerprintException) {
            throw IllegalStateException("front baseline already captured for this capsule", duplicate)
        }
    }

    /** Extracts the sender BACK baseline into the live capture session only. */
    suspend fun captureBack(capsuleId: String): String {
        requireValidCapsuleId(capsuleId)
        if (!persistence.hasBaseline(capsuleId, FingerprintOrigin.SENDER)) {
            throw IllegalStateException("front must be captured before the back")
        }
        val staged = extractor.extract(CaptureFingerprintSide.BACK)
        require(staged.side == CaptureFingerprintSide.BACK) { "extractor returned wrong side" }
        return captureStore.stage(staged.serializedBytes)
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
