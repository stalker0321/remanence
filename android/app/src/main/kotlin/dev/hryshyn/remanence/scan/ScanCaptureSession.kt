package dev.hryshyn.remanence.scan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One extracted FRONT of the physically scanned postcard. Mirrors the create-
 * session staged shape without any persistence: scan fingerprints live only
 * inside this session until matching finishes. FRONT-only (ADR-012, M2-F0-07).
 */
class ScannedSide(
    val profileId: String,
    val serializedBytes: ByteArray,
)

/** Extraction port over :core:recognition, identical to the create-session one. */
fun interface ScanSideExtractor {
    fun extract(): ScannedSide
}

/**
 * M2-F0-07 state machine for the FRONT-only scan flow, reusing the same
 * extraction components as the create session (docs/recognition.md section 5):
 * FRONT once, then the session is ready for local matching.
 * Order violations fail closed instead of silently re-capturing, an explicit
 * reset is the only way to restart, and consuming the session drops all
 * fingerprint material so nothing outlives the scan flow.
 */
class ScanCaptureSession(
    private val extractor: ScanSideExtractor,
) {

    var state: ScanSessionState by mutableStateOf(ScanSessionState.AWAITING_FRONT)
        private set

    var front: ScannedSide? by mutableStateOf(null)
        private set

    val readyForMatching: Boolean
        get() = state == ScanSessionState.READY_FOR_MATCHING

    fun captureFront(): ScannedSide {
        check(state == ScanSessionState.AWAITING_FRONT) {
            "front capture requires state AWAITING_FRONT but was $state"
        }
        val side = extractor.extract()
        try {
            side.validated()
        } catch (failure: Exception) {
            side.serializedBytes.fill(0)
            throw failure
        }
        front = side
        state = ScanSessionState.READY_FOR_MATCHING
        return side
    }

    /** Explicit user-driven restart (quality retry); wipes the captured FRONT. */
    fun reset() {
        front?.serializedBytes?.fill(0)
        front = null
        state = ScanSessionState.AWAITING_FRONT
    }

    /**
     * Ends the session (match finished or flow abandoned): state becomes
     * CONSUMED and every fingerprint buffer is zeroized before release.
     */
    fun consume() {
        front?.serializedBytes?.fill(0)
        front = null
        state = ScanSessionState.CONSUMED
    }

    private fun ScannedSide.validated(): ScannedSide {
        require(serializedBytes.isNotEmpty()) { "extracted fingerprint is empty" }
        return this
    }
}

enum class ScanSessionState {
    AWAITING_FRONT,
    READY_FOR_MATCHING,
    CONSUMED,
}
