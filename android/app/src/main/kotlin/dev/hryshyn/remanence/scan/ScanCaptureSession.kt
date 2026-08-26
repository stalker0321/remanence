package dev.hryshyn.remanence.scan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.hryshyn.remanence.core.recognition.FingerprintSide

/**
 * One extracted side of the physically scanned postcard. Mirrors the create-
 * session staged shape without any persistence: scan fingerprints live only
 * inside this session until matching finishes.
 */
class ScannedSide(
    val profileId: String,
    val side: FingerprintSide,
    val serializedBytes: ByteArray,
)

/** Extraction port over :core:recognition, identical to the create-session one. */
fun interface ScanSideExtractor {
    fun extract(side: FingerprintSide): ScannedSide
}

/**
 * M1-M11 state machine for the scan flow's two captures, reusing the same
 * extraction components as the create session (docs/recognition.md section 5):
 * FRONT first, then BACK, then the session is ready for local matching.
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

    var back: ScannedSide? by mutableStateOf(null)
        private set

    val readyForMatching: Boolean
        get() = state == ScanSessionState.READY_FOR_MATCHING

    fun captureFront(): ScannedSide {
        check(state == ScanSessionState.AWAITING_FRONT) {
            "front capture requires state AWAITING_FRONT but was $state"
        }
        val side = extractor.extract(FingerprintSide.FRONT).validated(FingerprintSide.FRONT)
        front = side
        state = ScanSessionState.AWAITING_BACK
        return side
    }

    fun captureBack(): ScannedSide {
        check(state == ScanSessionState.AWAITING_BACK) {
            "back capture requires state AWAITING_BACK but was $state"
        }
        val side = extractor.extract(FingerprintSide.BACK).validated(FingerprintSide.BACK)
        back = side
        state = ScanSessionState.READY_FOR_MATCHING
        return side
    }

    /** Explicit user-driven restart (quality retry); wipes both captured sides. */
    fun reset() {
        front = null
        back = null
        state = ScanSessionState.AWAITING_FRONT
    }

    /**
     * Ends the session (match finished or flow abandoned): state becomes
     * CONSUMED and every fingerprint reference is released.
     */
    fun consume() {
        front = null
        back = null
        state = ScanSessionState.CONSUMED
    }

    private fun ScannedSide.validated(expected: FingerprintSide): ScannedSide {
        require(side == expected) { "extractor returned wrong side" }
        require(serializedBytes.isNotEmpty()) { "extracted fingerprint is empty" }
        return this
    }
}

enum class ScanSessionState {
    AWAITING_FRONT,
    AWAITING_BACK,
    READY_FOR_MATCHING,
    CONSUMED,
}
