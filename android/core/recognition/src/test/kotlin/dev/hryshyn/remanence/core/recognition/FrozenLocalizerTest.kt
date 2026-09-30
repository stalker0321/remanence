package dev.hryshyn.remanence.core.recognition

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * S2 localizer contract tests. Asset bytes are read from the bundled APK
 * asset path (no network); inference runs through an injectable fake
 * runtime — the real ORT session is never constructed here.
 */
class FrozenLocalizerTest {

    @org.junit.jupiter.api.BeforeEach
    fun loadNative() {
        try {
            System.loadLibrary("opencv_java4100")
        } catch (error: UnsatisfiedLinkError) {
            // Another sandbox may have mapped the same .so.
            val alreadyLoaded = error.message?.contains("already loaded") == true
            org.junit.jupiter.api.Assumptions.assumeTrue(
                alreadyLoaded,
            ) { "desktop OpenCV natives unavailable: $error" }
        }
    }

    private fun assetFile(): File {
        var dir: File? = File("").absoluteFile
        repeat(8) {
            val cand = File(dir, "app/src/main/assets/" + FrozenLocalizer.MODEL_ASSET_PATH)
            if (cand.isFile) return cand
            // core/recognition module root also resolves directly.
            val alt = File(dir, "src/main/assets/" + FrozenLocalizer.MODEL_ASSET_PATH)
            if (alt.isFile) return alt
            dir = dir?.parentFile
        }
        error("bundled model not found under ${File("").absolutePath}")
    }

    private class FakeRuntime(
        var logits: FrozenLocalizer.MaskLogits = FrozenLocalizer.MaskLogits(FloatArray(0), 0, 0),
        var failure: Throwable? = null,
    ) : FrozenLocalizer.LocalizerRuntime {
        var calls = 0
        var closes = 0
        override fun infer(chw320: FloatArray): FrozenLocalizer.MaskLogits {
            calls++
            failure?.let { throw it }
            return logits
        }

        override fun close() {
            closes++
        }
    }

    private fun fullLogits(value: Float, h: Int = 320, w: Int = 320) =
        FrozenLocalizer.MaskLogits(FloatArray(h * w) { value }, h, w)

    // -- bundled asset ----------------------------------------------------

    @Test
    fun bundledModelIsByteExactToFreeze() {
        val f = assetFile()
        assertEquals(FrozenLocalizer.MODEL_SIZE_BYTES, f.length().toInt())
        val digest = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        assertEquals(
            FrozenLocalizer.MODEL_SHA256,
            digest.digest().joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun frozenConstants() {
        assertEquals(320, FrozenLocalizer.LOCALIZER_SIZE)
        assertEquals(0.5f, FrozenLocalizer.THRESHOLD)
        assertEquals(listOf(0.485f, 0.456f, 0.406f), FrozenLocalizer.NORMALIZE_MEAN.toList())
        assertEquals(listOf(0.229f, 0.224f, 0.225f), FrozenLocalizer.NORMALIZE_STD.toList())
        assertEquals("frozen-matcher/v1/model_fold0_seed0.onnx", FrozenLocalizer.MODEL_ASSET_PATH)
    }

    // -- letterbox plan parity --------------------------------------------

    @Test
    fun letterboxPlanVectors() {
        assertEquals(
            FrozenLocalizer.LetterboxPlan(0.08, 240, 320, 40, 0),
            FrozenLocalizer.planLetterbox(3000, 4000),
        )
        assertEquals(
            FrozenLocalizer.LetterboxPlan(320.0 / 960.0, 320, 240, 0, 40),
            FrozenLocalizer.planLetterbox(960, 720),
        )
        assertEquals(
            FrozenLocalizer.LetterboxPlan(0.32, 249, 320, 35, 0),
            FrozenLocalizer.planLetterbox(777, 1000),
        )
        assertEquals(
            FrozenLocalizer.LetterboxPlan(3.2, 320, 320, 0, 0),
            FrozenLocalizer.planLetterbox(100, 100),
        )
        assertEquals(
            FrozenLocalizer.LetterboxPlan(320.0 / 1920.0, 320, 180, 0, 70),
            FrozenLocalizer.planLetterbox(1920, 1080),
        )
    }

    @Test
    fun letterboxPlanRejectsNonPositive() {
        assertFailsWith<IllegalArgumentException> { FrozenLocalizer.planLetterbox(0, 100) }
        assertFailsWith<IllegalArgumentException> { FrozenLocalizer.planLetterbox(100, -1) }
    }

    // -- normalize parity (index-wise, BGR input as OpenCV delivers) -------

    @Test
    fun normalizeMatchesFrozenArithmetic() {
        fun norm(b: Int, g: Int, r: Int): FloatArray {
            val bytes = byteArrayOf(b.toByte(), g.toByte(), r.toByte())
            return FrozenLocalizer.normalizeBgrToChw(bytes, 1, 1)
        }
        val black = norm(0, 0, 0)
        assertTrue(abs(black[0] - -2.1179039f) < 1e-6f)
        assertTrue(abs(black[1] - -2.0357143f) < 1e-6f)
        assertTrue(abs(black[2] - -1.8044444f) < 1e-6f)
        val white = norm(255, 255, 255)
        assertTrue(abs(white[0] - 2.2489083f) < 1e-6f)
        assertTrue(abs(white[1] - 2.4285715f) < 1e-6f)
        assertTrue(abs(white[2] - 2.64f) < 1e-6f)
        // Channel order preserved per index: blue byte feeds MEAN[0].
        val blueOnly = norm(255, 0, 0)
        assertTrue(abs(blueOnly[0] - 2.2489083f) < 1e-6f)
        assertTrue(abs(blueOnly[2] - -1.8044444f) < 1e-6f)
        assertFailsWith<IllegalArgumentException> {
            FrozenLocalizer.normalizeBgrToChw(ByteArray(5), 1, 1)
        }
    }

    // -- threshold strictness ------------------------------------------------

    @Test
    fun thresholdIsStrictlyGreater() {
        val out = FrozenLocalizer.thresholdMask(floatArrayOf(0.5f, 0.50001f, 0.49999f, 10f, -10f))
        assertEquals(listOf<Byte>(0, 1, 0, 1, 0), out.toList())
    }

    // -- foreground window ---------------------------------------------------

    @Test
    fun foregroundWindowUsesExclusiveEnd() {
        val mask = ByteArray(9)
        mask[1] = 1 // (1,0)
        mask[8] = 1 // (2,2)
        assertEquals(
            FrozenLocalizer.ImageWindow(1, 0, 3, 3),
            FrozenLocalizer.foregroundWindow(mask, 3, 3),
        )
    }

    @Test
    fun emptyMaskYieldsNoWindow() {
        assertEquals(null, FrozenLocalizer.foregroundWindow(ByteArray(12), 4, 3))
    }

    // -- centres filter -------------------------------------------------------

    @Test
    fun centresInsideKeepsForegroundCentresOnly() {
        val mask = ByteArray(100 * 100)
        mask[50 * 100 + 50] = 1
        mask[50 * 100 + 51] = 1
        val points = listOf(
            FrozenLocalizer.PointF(50f, 50f), // inside
            FrozenLocalizer.PointF(10f, 10f), // outside mask
            FrozenLocalizer.PointF(-1f, 50f), // out of bounds
            FrozenLocalizer.PointF(50f, 500f), // out of bounds
            FrozenLocalizer.PointF(Float.NaN, 50f), // non-finite dropped
            FrozenLocalizer.PointF(50.5f, 50f), // Python round(50.5)=50 -> inside
        )
        val kept = FrozenLocalizer.centresInside(points, mask, 100, 100)
        assertEquals(2, kept.size)
        assertEquals(50f, kept[0].x)
        assertEquals(50.5f, kept[1].x)
    }

    // -- session behavior through the fake ------------------------------------

    private fun frame4x3(): ByteArray = ByteArray(3 * 4 * 3)

    @Test
    fun fullMaskProducesFullFrameProposal() {
        val fake = FakeRuntime(fullLogits(10f))
        val session = FrozenLocalizerSession(fake)
        val result = session.localize(frame4x3(), 4, 3)
        assertTrue(result is FrozenLocalizer.LocalizerResult.Proposal)
        assertEquals(FrozenLocalizer.ImageWindow(0, 0, 4, 3), result.window)
        assertEquals(1, fake.calls)
        session.close()
    }

    @Test
    fun zeroLogitIsExcludedByStrictThreshold() {
        val fake = FakeRuntime(fullLogits(0f))
        val session = FrozenLocalizerSession(fake)
        // sigmoid(0) == 0.5, and 0.5 is NOT > 0.5: empty mask.
        val result = session.localize(frame4x3(), 4, 3)
        assertEquals(
            FrozenLocalizer.LocalizerResult.Unknown(FrozenLocalizer.Reasons.EMPTY_MASK),
            result,
        )
        session.close()
    }

    @Test
    fun emptyMaskIsUnknown() {
        val fake = FakeRuntime(fullLogits(-10f))
        FrozenLocalizerSession(fake).use { session ->
            assertEquals(
                FrozenLocalizer.LocalizerResult.Unknown(FrozenLocalizer.Reasons.EMPTY_MASK),
                session.localize(frame4x3(), 4, 3),
            )
        }
    }

    @Test
    fun nanLogitsAreUnknown() {
        val fake = FakeRuntime(fullLogits(Float.NaN))
        FrozenLocalizerSession(fake).use { session ->
            assertEquals(
                FrozenLocalizer.LocalizerResult.Unknown(FrozenLocalizer.Reasons.BAD_LOGITS),
                session.localize(frame4x3(), 4, 3),
            )
        }
    }

    @Test
    fun misshapenLogitsAreUnknown() {
        val fake = FakeRuntime(FrozenLocalizer.MaskLogits(FloatArray(10), 4, 3))
        FrozenLocalizerSession(fake).use { session ->
            assertEquals(
                FrozenLocalizer.LocalizerResult.Unknown(FrozenLocalizer.Reasons.BAD_LOGITS),
                session.localize(frame4x3(), 4, 3),
            )
        }
    }

    @Test
    fun runtimeExceptionIsUnknownWithoutPixels() {
        val fake = FakeRuntime(failure = RuntimeException("boom"))
        FrozenLocalizerSession(fake).use { session ->
            val result = session.localize(frame4x3(), 4, 3)
            assertEquals(
                FrozenLocalizer.LocalizerResult.Unknown(FrozenLocalizer.Reasons.RUNTIME),
                result,
            )
        }
    }

    @Test
    fun badDimsAndBytesAreUnknown() {
        val fake = FakeRuntime(fullLogits(10f))
        FrozenLocalizerSession(fake).use { session ->
            assertEquals(
                FrozenLocalizer.LocalizerResult.Unknown(FrozenLocalizer.Reasons.BAD_DIMS),
                session.localize(frame4x3(), 0, 3),
            )
            assertEquals(
                FrozenLocalizer.LocalizerResult.Unknown(FrozenLocalizer.Reasons.BAD_BYTES),
                session.localize(ByteArray(5), 4, 3),
            )
            assertEquals(0, fake.calls)
        }
    }

    @Test
    fun closedSessionIsUnknownAndCloseIsIdempotent() {
        val fake = FakeRuntime(fullLogits(10f))
        val session = FrozenLocalizerSession(fake)
        session.close()
        session.close()
        assertEquals(
            FrozenLocalizer.LocalizerResult.Unknown(FrozenLocalizer.Reasons.CLOSED),
            session.localize(frame4x3(), 4, 3),
        )
        assertEquals(0, fake.calls)
    }

    @Test
    fun cancellationPropagatesAndSessionStaysCloseable() {
        val fake = FakeRuntime(fullLogits(10f))
        val session = FrozenLocalizerSession(fake, isCancelled = { true })
        assertFailsWith<CancellationException> { session.localize(frame4x3(), 4, 3) }
        session.close()
        assertEquals(1, fake.closes)
    }

    @Test
    fun emptyModelBytesAreRejectedBeforeRuntime() {
        assertFailsWith<IllegalArgumentException> { OrtLocalizerRuntime.open(ByteArray(0)) }
    }
}
