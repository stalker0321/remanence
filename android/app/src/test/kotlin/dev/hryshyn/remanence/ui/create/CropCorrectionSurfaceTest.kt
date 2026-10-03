package dev.hryshyn.remanence.ui.create

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.CropTransform
import dev.hryshyn.remanence.ui.collage.FixedFrameCropCorrection
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * T150 crop stage surface — the B1 fixed-frame contract:
 * - the frame keeps the **slot aspect** (`--crop-ratio: slot.w/slot.h`);
 * - the source excerpt is **clipped** to that frame (the frame edge is the
 *   retained window), never a source-aspect unclipped preview;
 * - the `.crop-grid` overlay (thirds + border) sits on the frame;
 * - the retained window shown to the user is derived with the same accepted
 *   T157 `sourceWindow` math the ViewModel seals, so display and sealed
 *   window can never disagree;
 * - zoom control, the four direction buttons (T157 `panDelta`) and
 *   done/reset/back sinks work.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CropCorrectionSurfaceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Destination slot geometry (the frame ratio comes from the placement). */
    private val slotWidth = 340f
    private val slotHeight = 248f
    private val slotAspect = slotWidth / slotHeight

    private fun jpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF669933.toInt())
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        bitmap.recycle()
        return output.toByteArray()
    }

    private fun source() = GeneratorPreviewSource(
        contentId = "photo-0",
        jpegBytes = jpeg(),
        widthPx = 1000,
        heightPx = 1000,
    )

    // A partial approved window: its excerpt overflows the fixed frame, which
    // is what the clip assertion observes.
    private val baseCrop = GeneratorExpression.CropWindow(500, 500, 6_000, 6_000)

    private fun stage(
        correction: CreateViewModel.CropCorrection?,
        updates: MutableList<Triple<Float, Float, Float>> = mutableListOf(),
        resets: MutableList<Int> = mutableListOf(),
        dones: MutableList<Int> = mutableListOf(),
        backs: MutableList<Int> = mutableListOf(),
    ) {
        composeRule.setContent {
            // Production mounts the stage inside CreateScreen's scrollable
            // root; the test mirrors that so the bottom action row is
            // reachable on small windows.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CropStageContent(
                    source = source(),
                    slotAspect = slotAspect,
                    baseCrop = baseCrop,
                    correction = correction,
                    onUpdate = { zoom, panX, panY -> updates += Triple(zoom, panX, panY) },
                    onReset = { resets += 1 },
                    onDone = { dones += 1 },
                    onBack = { backs += 1 },
                )
            }
        }
    }

    private fun DpRect.ratio(): Float =
        if (height.value == 0f) Float.NaN else width.value / height.value

    @Test
    fun cropStageRendersFrameControlsAndSinks() {
        val updates = mutableListOf<Triple<Float, Float, Float>>()
        val resets = mutableListOf<Int>()
        val dones = mutableListOf<Int>()
        val backs = mutableListOf<Int>()
        stage(
            correction = CreateViewModel.CropCorrection("photo-0", 1.1f, 2f, 3f),
            updates = updates,
            resets = resets,
            dones = dones,
            backs = backs,
        )

        composeRule.onNodeWithTag("crop_stage").assertExists()
        composeRule.onNodeWithTag("crop_frame").assertExists()
        composeRule.onNodeWithTag("crop_grid").assertExists()
        composeRule.onNodeWithTag("crop_stage_hint").assertExists()
        composeRule.onNodeWithTag("crop_zoom").assertExists()
        composeRule.onNodeWithTag("crop_pan_left").assertExists()
        composeRule.onNodeWithTag("crop_pan_up").assertExists()
        composeRule.onNodeWithTag("crop_pan_down").assertExists()
        composeRule.onNodeWithTag("crop_pan_right").assertExists()

        // One direction press is exactly one T157 frame percent on one axis.
        composeRule.onNodeWithTag("crop_pan_right").performClick()
        assertEquals(listOf(Triple(1.1f, 3f, 3f)), updates)

        composeRule.onNodeWithTag("crop_back").performScrollTo()
        composeRule.onNodeWithTag("crop_back").performClick()
        assertEquals(listOf(1), backs)

        composeRule.onNodeWithTag("crop_reset").performScrollTo()
        composeRule.onNodeWithTag("crop_reset").performClick()
        assertEquals(listOf(1), resets)

        composeRule.onNodeWithTag("crop_done").performScrollTo()
        composeRule.onNodeWithTag("crop_done").performClick()
        assertEquals(listOf(1), dones)
    }

    @Test
    fun frameKeepsSlotAspectAndClipsTheSource() {
        stage(correction = null)

        val frame: DpRect = composeRule.onNodeWithTag("crop_frame").getUnclippedBoundsInRoot()
        assertEquals("frame must keep the slot aspect", slotAspect, frame.ratio(), 0.01f)

        // The grid overlay covers the frame exactly (inset 0 in the CSS).
        val grid: DpRect = composeRule.onNodeWithTag("crop_grid").getUnclippedBoundsInRoot()
        assertTrue("grid width", absDp(grid.width - frame.width) <= Dp(1f))
        assertTrue("grid height", absDp(grid.height - frame.height) <= Dp(1f))

        // The source excerpt overflows the frame (a base crop that does not
        // fill the window still draws beyond it) but is CLIPPED to it.
        val image = composeRule.onNodeWithTag("crop_image")
        val imageUnclipped: DpRect = image.getUnclippedBoundsInRoot()
        val imageClipped: DpRect = image.getBoundsInRoot()
        assertTrue(
            "clipped image must stay inside the fixed frame; frame=$frame clipped=$imageClipped unclipped=$imageUnclipped",
            imageClipped.left >= frame.left - Dp(1f) &&
                imageClipped.top >= frame.top - Dp(1f) &&
                imageClipped.right <= frame.right + Dp(1f) &&
                imageClipped.bottom <= frame.bottom + Dp(1f),
        )
        assertTrue(
            "the unclipped excerpt must be larger than the window; frame=$frame unclipped=$imageUnclipped base=$baseCrop",
            imageUnclipped.width > frame.width || imageUnclipped.height > frame.height,
        )
    }

    @Test
    fun retainedWindowIsIdentityWithoutCorrection() {
        val base = requireNotNull(FixedFrameCropCorrection.normalizedCrop(baseCrop))
        val identity = requireNotNull(
            FixedFrameCropCorrection.sourceWindow(base, CropTransform(1.0, 0.0, 0.0)),
        )
        stage(correction = null)

        // Identity: the frame shows exactly the approved window, derived with
        // the accepted T157 sourceWindow (the same call the ViewModel seals).
        composeRule.onNode(stateMatches(windowState(identity))).assertExists()
    }

    @Test
    fun retainedWindowFollowsZoomAndPanFromT157Math() {
        val base = requireNotNull(FixedFrameCropCorrection.normalizedCrop(baseCrop))
        val identity = requireNotNull(
            FixedFrameCropCorrection.sourceWindow(base, CropTransform(1.0, 0.0, 0.0)),
        )
        stage(correction = CreateViewModel.CropCorrection("photo-0", 1.25f, 5f, 0f))
        val zoomed = requireNotNull(
            FixedFrameCropCorrection.sourceWindow(base, CropTransform(1.25, 5.0, 0.0)),
        )
        val zoomOnly = requireNotNull(
            FixedFrameCropCorrection.sourceWindow(base, CropTransform(1.25, 0.0, 0.0)),
        )

        assertTrue("zoom must narrow the window", zoomed.width < identity.width)
        // Positive x percent moves the window LEFT relative to the pure-zoom
        // window (T157 sourceWindow sign), not necessarily past base.x.
        assertTrue(
            "pan must shift the retained window left of the zoom-only window",
            zoomed.x < zoomOnly.x,
        )
        composeRule.onNode(stateMatches(windowState(zoomed))).assertExists()
        // ...and no longer the identity window.
        composeRule.onNode(stateMatches(windowState(identity))).assertDoesNotExist()
    }

    @Test
    fun nonSquareSlotIsAcceptedAndKeptAsFrameRatio() {
        val portraitAspect = 167f / 248f
        composeRule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CropStageContent(
                    source = source(),
                    slotAspect = portraitAspect,
                    baseCrop = baseCrop,
                    correction = null,
                    onUpdate = { _, _, _ -> },
                    onReset = {},
                    onDone = {},
                    onBack = {},
                )
            }
        }
        val frame = composeRule.onNodeWithTag("crop_frame").getUnclippedBoundsInRoot()
        assertEquals(portraitAspect, frame.ratio(), 0.01f)
        composeRule.onNodeWithTag("crop_grid").assertIsDisplayed()
    }

    @Test
    fun stageControlsDeliverToCallbacks() {
        val updates = mutableListOf<Triple<Float, Float, Float>>()
        val resets = mutableListOf<Int>()
        val dones = mutableListOf<Int>()
        val backs = mutableListOf<Int>()
        stage(null, updates, resets, dones, backs)
        composeRule.onNodeWithTag("crop_pan_right").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("crop_reset").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("crop_back").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("crop_done").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(Triple(1f, 1f, 0f)), updates)
        assertEquals(listOf(1), resets)
        assertEquals(listOf(1), backs)
        assertEquals(listOf(1), dones)
    }

    @Test
    fun slotPickerOpensSelectedSlot() {
        var opened: String? = null
        val second = GeneratorPreviewSource("photo-1", jpeg(), 1000, 1000)
        composeRule.setContent {
            CropSlotPicker(
                sources = listOf(source(), second),
                corrections = mapOf("photo-0" to CreateViewModel.CropCorrection("photo-0", 1.2f, 0f, 0f)),
                onOpen = { opened = it },
            )
        }

        composeRule.onNodeWithTag("crop_slots").assertExists()
        composeRule.onNodeWithTag("crop_slot_1").performClick()
        assertEquals("photo-1", opened)
    }

    @Test
    fun fiveSlotPickerKeepsEverySlotReachable() {
        var opened: String? = null
        val five = (0..4).map { GeneratorPreviewSource("photo-$it", jpeg(), 1000, 1000) }
        composeRule.setContent {
            Column(Modifier.width(320.dp)) {
                CropSlotPicker(
                    sources = five,
                    corrections = emptyMap(),
                    onOpen = { opened = it },
                )
            }
        }

        composeRule.onNodeWithTag("crop_slots").assertExists()
        // The trailing slot of five must be reachable (scrollable row,
        // never clipped away) and open its own content id.
        composeRule.onNodeWithTag("crop_slot_4").performScrollTo()
        composeRule.onNodeWithTag("crop_slot_4").assertIsDisplayed()
        composeRule.onNodeWithTag("crop_slot_4").performClick()
        assertEquals("photo-4", opened)
    }

    private fun absDp(value: Dp): Dp = if (value.value < 0f) -value else value

    // ---- Raster oracle (lead crop-raster brief) ----

    /** Lossless coordinate gradient: R=x, G=y (B=128) so every pixel is
     * position-decodable. JPEG would add lossy noise; PNG is exact. */
    private fun gradientPng(size: Int = 64): ByteArray {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) {
            for (x in 0 until size) {
                bitmap.setPixel(
                    x,
                    y,
                    android.graphics.Color.argb(
                        255,
                        x * 255 / (size - 1),
                        y * 255 / (size - 1),
                        128,
                    ),
                )
            }
        }
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        return output.toByteArray()
    }

    private fun gradientSource() = GeneratorPreviewSource(
        contentId = "photo-0",
        jpegBytes = gradientPng(),
        widthPx = 64,
        heightPx = 64,
    )

    private fun rasterStage(
        source: GeneratorPreviewSource,
        baseCrop: GeneratorExpression.CropWindow,
        correction: CreateViewModel.CropCorrection?,
    ) {
        composeRule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CropStageContent(
                    source = source,
                    slotAspect = slotAspect,
                    baseCrop = baseCrop,
                    correction = correction,
                    onUpdate = { _, _, _ -> },
                    onReset = {},
                    onDone = {},
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private data class FramePixels(val width: Int, val height: Int, val argb: IntArray)

    private fun captureFrame(): FramePixels {
        // Repo-proven NATIVE capture (same as host pixel tests):
        // draw the decor view, then crop the frame bounds.
        val node = composeRule.onNodeWithTag("crop_frame").fetchSemanticsNode()
        var out = FramePixels(0, 0, IntArray(0))
        composeRule.runOnIdle {
            val view = composeRule.activity.window.decorView
            val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(full))
            val b = node.boundsInRoot
            val left = b.left.toInt().coerceIn(0, full.width - 1)
            val top = b.top.toInt().coerceIn(0, full.height - 1)
            val right = b.right.toInt().coerceIn(left + 1, full.width)
            val bottom = b.bottom.toInt().coerceIn(top + 1, full.height)
            val w = right - left
            val h = bottom - top
            val buf = IntArray(w * h)
            full.getPixels(buf, 0, w, left, top, w, h)
            out = FramePixels(w, h, buf)
        }
        return out
    }

    /**
     * Independent frozen-excerpt oracle (transcribed from the frozen
     * spec, not calling production `sourceWindow`): the base excerpt is
     * laid at exact size and placed top-left plus the manual offset —
     * i.e. frame pixel (fx, fy) samples the source at the excerpted
     * image pixel (fx − offsetX, fy − offsetY). The stage then applies
     * the correction as translate(x%,y%) scale(z) about the frame
     * CENTER, so the full inverse mapping is
     * content = (framePoint − C − t)/z + C mapped into the excerpt:
     * u = bx + ((fx − Cx − tx)/z + Cx)·bw/Wf (and likewise v).
     * Returns expected (R,G,B) for a frame pixel over the 0..255
     * gradient, or null outside the source.
     */
    private fun oraclePixel(
        fx: Float, fy: Float, frameW: Float, frameH: Float,
        bx: Double, by: Double, bw: Double, bh: Double,
        z: Double, xPct: Double, yPct: Double,
    ): Triple<Int, Int, Int>? {
        val tx = xPct / 100.0 * frameW
        val ty = yPct / 100.0 * frameH
        val sx = ((fx - frameW / 2f - tx) / z + frameW / 2f).toDouble()
        val sy = ((fy - frameH / 2f - ty) / z + frameH / 2f).toDouble()
        val u = bx + sx * bw / frameW
        val v = by + sy * bh / frameH
        if (u < 0.0 || u > 1.0 || v < 0.0 || v > 1.0) return null
        return Triple(
            (u * 255.0).toInt().coerceIn(0, 255),
            (v * 255.0).toInt().coerceIn(0, 255),
            128,
        )
    }

    /** Independent frozen sourceWindow transcription (spec formula). */
    private fun oracleWindow(
        bx: Double, by: Double, bw: Double, bh: Double,
        z: Double, xPct: Double, yPct: Double,
    ): DoubleArray {
        val inv = 1.0 / z
        return doubleArrayOf(
            bx + bw * (0.5 - (0.5 + xPct / 100.0) * inv),
            by + bh * (0.5 - (0.5 + yPct / 100.0) * inv),
            bw * inv,
            bh * inv,
        )
    }

    /** Sample points avoiding grid thirds lines and the frame border. */
    private fun samplePoints(w: Int, h: Int): List<Pair<Int, Int>> {
        val pts = mutableListOf<Pair<Int, Int>>()
        for (i in 1..11 step 2) {
            for (j in 1..11 step 2) {
                pts += (w * i / 12) to (h * j / 12)
            }
        }
        return pts
    }

    private fun argbAt(pixels: FramePixels, x: Int, y: Int): Triple<Int, Int, Int> {
        val p = pixels.argb[y * pixels.width + x]
        return Triple((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
    }

    @Test
    fun excerptPlacementMatchesFrozenOracle() {
        // Partial nonzero-origin base, oversized both axes: a hidden
        // automatic centering would shift every sample by ~(oversize/2)
        // (~50 LSB here) — the oracle pins top-left + offset instead.
        val base = GeneratorExpression.CropWindow(1000, 1000, 6855, 5000)
        rasterStage(gradientSource(), base, null)
        val frame = captureFrame()
        assertTrue("frame must have area", frame.width > 50 && frame.height > 50)
        var maxErr = 0
        var sumErr = 0L
        var n = 0
        for ((x, y) in samplePoints(frame.width, frame.height)) {
            val expected = oraclePixel(
                x.toFloat(), y.toFloat(), frame.width.toFloat(), frame.height.toFloat(),
                0.1, 0.1, 0.6855, 0.5, 1.0, 0.0, 0.0,
            ) ?: continue
            val actual = argbAt(frame, x, y)
            val err = abs(expected.first - actual.first) +
                abs(expected.second - actual.second) +
                abs(expected.third - actual.third)
            maxErr = maxOf(maxErr, err)
            sumErr += err
            n += 1
        }
        assertTrue("expected samples, got none", n > 20)
        // Bilinear + rounding on a linear gradient: single LSBs. A
        // placement mismatch (e.g. centered instead of top-left with
        // offset) would read tens of LSB per sample.
        assertTrue(
            "max sample error $maxErr over $n points (placement must be top-left + offset)",
            maxErr <= 12,
        )
        assertTrue(
            "mean sample error ${sumErr.toDouble() / n} over $n points",
            sumErr.toDouble() / n <= 4.0,
        )
    }

    @Test
    fun excerptPlacementMatchesFrozenOracleTransformed() {
        // Same placement proof under a real zoom+pan transform.
        val base = GeneratorExpression.CropWindow(1000, 1000, 6855, 5000)
        val correction = CreateViewModel.CropCorrection("photo-0", 1.1f, 2f, -1f)
        rasterStage(gradientSource(), base, correction)
        val frame = captureFrame()
        var maxErr = 0
        var sumErr = 0L
        var n = 0
        for ((x, y) in samplePoints(frame.width, frame.height)) {
            val expected = oraclePixel(
                x.toFloat(), y.toFloat(), frame.width.toFloat(), frame.height.toFloat(),
                0.1, 0.1, 0.6855, 0.5, 1.1, 2.0, -1.0,
            ) ?: continue
            val actual = argbAt(frame, x, y)
            val err = abs(expected.first - actual.first) +
                abs(expected.second - actual.second) +
                abs(expected.third - actual.third)
            maxErr = maxOf(maxErr, err)
            sumErr += err
            n += 1
        }
        assertTrue("expected samples, got none", n > 20)
        assertTrue(
            "max sample error $maxErr over $n points under transform",
            maxErr <= 12,
        )
        assertTrue(
            "mean sample error ${sumErr.toDouble() / n} over $n points under transform",
            sumErr.toDouble() / n <= 4.0,
        )
    }

    @Test
    fun interactiveMatchesReopenedBakedRaster() {
        // Baked window from the INDEPENDENT oracle (not production math):
        // interactive(base, T) vs reopened(baked, identity) must agree to
        // quantization + resampling noise. A placement mismatch (e.g.
        // hidden centering) would move every pixel by tens of LSB.
        // Single setContent with state-driven params (rule allows one).
        val z = 1.1
        val xPct = 2.0
        val yPct = -1.0
        val baked = oracleWindow(0.1, 0.1, 0.6855, 0.5, z, xPct, yPct)
        fun quantize(v: Double) = (v * 10000.0).roundToInt()
        val bakedCrop = GeneratorExpression.CropWindow(
            quantize(baked[0]), quantize(baked[1]),
            quantize(baked[2]), quantize(baked[3]),
        )
        val base0 = GeneratorExpression.CropWindow(1000, 1000, 6855, 5000)
        val corrT = CreateViewModel.CropCorrection("photo-0", z.toFloat(), xPct.toFloat(), yPct.toFloat())
        var baseArg by mutableStateOf(base0)
        var correctionArg: CreateViewModel.CropCorrection? by mutableStateOf(corrT)
        val src = gradientSource()
        composeRule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CropStageContent(
                    source = src,
                    slotAspect = slotAspect,
                    baseCrop = baseArg,
                    correction = correctionArg,
                    onUpdate = { _, _, _ -> },
                    onReset = {},
                    onDone = {},
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()
        val interactive = captureFrame()
        composeRule.runOnIdle {
            baseArg = bakedCrop
            correctionArg = null
        }
        composeRule.waitForIdle()
        val reopened = captureFrame()
        assertEquals(interactive.width, reopened.width)
        assertEquals(interactive.height, reopened.height)
        var sum = 0L
        var max = 0
        val n = interactive.width * interactive.height
        for (i in 0 until n) {
            val a = interactive.argb[i]
            val b = reopened.argb[i]
            val d = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
                abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
                abs((a and 0xFF) - (b and 0xFF))
            sum += d
            if (d > max) max = d
        }
        val mean = sum.toDouble() / n
        // Quantization (1e-4) + resampling-grid realignment on a smooth
        // gradient: single LSBs. Placement-level mismatch reads tens.
        assertTrue("interactive vs reopened mean channel diff $mean", mean <= 3.0)
        assertTrue("interactive vs reopened max channel diff $max", max <= 24)
    }

    /** Matcher for the frame's retained-window state description. */
    private fun stateMatches(value: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)
}
