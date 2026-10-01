package dev.hryshyn.remanence.scan.matcher

import android.graphics.Bitmap
import dev.hryshyn.remanence.core.recognition.CaptureDecoder
import dev.hryshyn.remanence.core.recognition.FrozenLocalizer
import dev.hryshyn.remanence.core.recognition.FrozenLocalizerSession
import dev.hryshyn.remanence.core.recognition.OrtLocalizerRuntime
import dev.hryshyn.remanence.core.recognition.StrictCombinedDecision
import dev.hryshyn.remanence.core.recognition.StrictCombinedEvidence
import dev.hryshyn.remanence.core.recognition.StrictCombinedGate
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * One owned shadow frame: upright BGR bytes (3 bytes per pixel, the exact
 * layout `FrozenLocalizerSession` documents) plus its dimensions. No JPEG,
 * path, or identity is retained. [wipe] is idempotent.
 */
class MatcherShadowFrame(
    val bgr: ByteArray,
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0) { "frame dimensions must be positive" }
        require(bgr.size == 3 * width * height) { "frame must hold exactly 3*w*h bytes" }
    }

    @Volatile
    private var wiped = false

    val isWiped: Boolean get() = wiped

    fun wipe() {
        if (!wiped) {
            wiped = true
            bgr.fill(0)
        }
    }
}

/** Bounded S2 outcome; the frozen `Reasons` strings are deliberately dropped. */
sealed interface MatcherShadowLocalizerOutcome {
    object Proposal : MatcherShadowLocalizerOutcome

    object Unknown : MatcherShadowLocalizerOutcome
}

/**
 * The injectable seam around the accepted S1->S4 code. Production binds the
 * real accepted pipeline; hermetic tests bind fakes. No seam can express a
 * grant, chooser, recapture, or any v1 decision - the strongest thing a seam
 * can return is [StrictCombinedDecision] from S4, which the pipeline reduces
 * to bounded scalars and discards.
 */
interface MatcherShadowStages {
    /**
     * Decode capture JPEG bytes to an upright BGR frame at the frozen decode
     * bound, or return null when the bytes are not decodable.
     */
    fun decode(jpeg: ByteArray): MatcherShadowFrame?

    /** S2 frozen localizer over the S1 frame crop. */
    fun localize(frameBgr: ByteArray, width: Int, height: Int): MatcherShadowLocalizerOutcome

    /**
     * S5 catalog delivery seam. Returns null while no provenance-valid
     * catalog has been delivered (T017/T081/T116: never fabricate, never
     * substitute - absence is reported as a bounded unavailable outcome).
     */
    fun catalogIndex(): ByteArray?

    /** S4 frozen gate; callers pass only honest inputs, never fabricated evidence. */
    fun gate(baselineAccepted: Boolean, evidence: StrictCombinedEvidence?): StrictCombinedDecision
}

/**
 * Production binding of [MatcherShadowStages] to the accepted code:
 *
 * * decode - [CaptureDecoder] (accepted upright decode) sampled to the frozen
 *   `DECODE_MAX_SIDE` of 960 with an exact long-edge fit, then ARGB -> BGR,
 *   matching the frozen `vision.decode(max_side=960)` contract shape.
 * * S1/S2/S4 - accepted [dev.hryshyn.remanence.core.recognition.FrameGeometry]
 *   (called directly by the pipeline), [FrozenLocalizerSession] over the
 *   bundled 12.9 MB seed-0 model, and [StrictCombinedGate] unchanged.
 * * S3 catalog - the injected provider; production passes a provider that
 *   returns null until the lead delivers provenance-valid catalog bytes.
 *
 * Every native/third-party failure is mapped to a bounded outcome; Java/Kotlin
 * cancellation is never swallowed.
 */
class AndroidMatcherShadowStages(
    private val modelBytes: () -> ByteArray?,
    private val catalogIndex: () -> ByteArray? = { null },
    private val decodeMaxSidePx: Int = FROZEN_DECODE_MAX_SIDE_PX,
) : MatcherShadowStages {

    init {
        require(decodeMaxSidePx > 0) { "decodeMaxSidePx must be positive" }
    }

    override fun decode(jpeg: ByteArray): MatcherShadowFrame? = try {
        val decoded = CaptureDecoder(decodeMaxSidePx).decode(jpeg)
        var bitmap = decoded.bitmap
        try {
            val longEdge = maxOf(bitmap.width, bitmap.height)
            if (longEdge > decodeMaxSidePx) {
                val scale = decodeMaxSidePx.toDouble() / longEdge
                val targetWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
                val targetHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }
            val width = bitmap.width
            val height = bitmap.height
            val argb = IntArray(width * height)
            bitmap.getPixels(argb, 0, width, 0, 0, width, height)
            val bgr = ByteArray(3 * width * height)
            var out = 0
            for (pixel in argb) {
                bgr[out++] = (pixel and 0xFF).toByte()
                bgr[out++] = ((pixel shr 8) and 0xFF).toByte()
                bgr[out++] = ((pixel shr 16) and 0xFF).toByte()
            }
            MatcherShadowFrame(bgr, width, height)
        } finally {
            bitmap.recycle()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    override fun localize(
        frameBgr: ByteArray,
        width: Int,
        height: Int,
    ): MatcherShadowLocalizerOutcome {
        val bytes = modelBytes() ?: return MatcherShadowLocalizerOutcome.Unknown
        val runtime = try {
            OrtLocalizerRuntime.open(bytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return MatcherShadowLocalizerOutcome.Unknown
        }
        val session = FrozenLocalizerSession(runtime)
        try {
            return when (val result = session.localize(frameBgr, width, height)) {
                is FrozenLocalizer.LocalizerResult.Proposal -> {
                    // Bounded wrapper payload: wipe, never retain mask pixels.
                    result.mask.fill(0)
                    MatcherShadowLocalizerOutcome.Proposal
                }
                is FrozenLocalizer.LocalizerResult.Unknown -> MatcherShadowLocalizerOutcome.Unknown
            }
        } finally {
            session.close()
        }
    }

    override fun catalogIndex(): ByteArray? = catalogIndex.invoke()

    override fun gate(
        baselineAccepted: Boolean,
        evidence: StrictCombinedEvidence?,
    ): StrictCombinedDecision = StrictCombinedGate.decide(baselineAccepted, evidence)

    companion object {
        /** Frozen `DECODE_MAX_SIDE` (PRODUCTION_FREEZE_SPEC section 3). */
        const val FROZEN_DECODE_MAX_SIDE_PX: Int = 960
    }
}
