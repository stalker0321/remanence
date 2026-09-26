package dev.hryshyn.remanence.ui.collage.analysis

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure (no OpenCV) oracle tests for [YuNetFacePostProcessor]: Python half-even
 * resize, inverse-mapped/clamped boxes, degenerate rejection, stable
 * confidence-descending dedup, and a source pin that the real runtime releases
 * every reshape header in `finally`.
 */
class YuNetFacePostProcessorTest {

    @Test
    fun letterboxSizeMatchesHalfEvenResize() {
        assertEquals(640 to 640, YuNetFacePostProcessor.letterboxSize(1000, 1000, 640))
        assertEquals(512 to 640, YuNetFacePostProcessor.letterboxSize(800, 1000, 640))
        // 665 * 0.5 = 332.5 -> half-even 332; 667 * 0.5 = 333.5 -> 334.
        assertEquals(332 to 416, YuNetFacePostProcessor.letterboxSize(665, 832, 416))
        assertEquals(334 to 416, YuNetFacePostProcessor.letterboxSize(667, 832, 416))
    }

    @Test
    fun normalizeMapsAndClampsToUprightSource() {
        val pass = YuNetPass(640, 480, 15, listOf(row(64f, 48f, 128f, 96f, 0.9f)))
        val faces = YuNetFacePostProcessor.normalize(pass)
        assertEquals(1, faces.size)
        val box = faces[0].box
        assertEquals(0.1, box.x, 1e-6)
        assertEquals(0.1, box.y, 1e-6)
        assertEquals(0.2, box.width, 1e-6)
        assertEquals(0.2, box.height, 1e-6)
        assertEquals(0.9, faces[0].confidence, 1e-6)
    }

    @Test
    fun normalizeClampsBoxesToUnitSquare() {
        val pass = YuNetPass(640, 480, 15, listOf(row(600f, 400f, 200f, 200f, 0.9f)))
        val box = YuNetFacePostProcessor.normalize(pass).single().box
        assertEquals(0.9375, box.x, 1e-6)
        assertEquals(0.833333, box.y, 1e-4)
        assertEquals(0.0625, box.width, 1e-6)
        assertEquals(0.166667, box.height, 1e-4)
    }

    @Test
    fun normalizeDropsDegenerateBoxes() {
        val narrow = YuNetPass(640, 480, 15, listOf(row(10f, 10f, 2f, 96f, 0.99f)))
        assertEquals(1, YuNetFacePostProcessor.normalize(narrow).size)
        val zeroWidth = YuNetPass(640, 480, 15, listOf(row(10f, 10f, 0f, 96f, 0.99f)))
        assertTrue(YuNetFacePostProcessor.normalize(zeroWidth).isEmpty())
        val clampedEmpty = YuNetPass(640, 480, 15, listOf(row(800f, 10f, 100f, 96f, 0.99f)))
        assertTrue(YuNetFacePostProcessor.normalize(clampedEmpty).isEmpty())
    }

    @Test
    fun normalizeRejectsTooFewColumns() {
        val pass = YuNetPass(640, 640, 14, listOf(FloatArray(14)))
        assertThrows { YuNetFacePostProcessor.normalize(pass) }
    }

    @Test
    fun normalizeRejectsNonFiniteRows() {
        val values = row(64f, 64f, 128f, 128f, 0.9f)
        values[5] = Float.NaN
        assertThrows { YuNetFacePostProcessor.normalize(YuNetPass(640, 640, 15, listOf(values))) }
    }

    @Test
    fun normalizeEmptyPassIsEmpty() {
        assertTrue(YuNetFacePostProcessor.normalize(YuNetPass(640, 640, 15, emptyList())).isEmpty())
        assertTrue(YuNetFacePostProcessor.normalize(YuNetPass(640, 640, 0, emptyList())).isEmpty())
    }

    @Test
    fun mergeDeduplicatesGreedilyByConfidence() {
        val pass = YuNetPass(
            640, 640, 15,
            listOf(
                row(256f, 256f, 192f, 192f, 0.5f), // A lower score
                row(0f, 0f, 128f, 128f, 0.9f), // B highest
                row(0f, 0f, 128f, 128f, 0.7f), // C duplicate of B
            ),
        )
        val merged = YuNetFacePostProcessor.merge(listOf(pass), dedupIou = 0.25)
        assertEquals(2, merged.size)
        assertEquals(0.9, merged[0].confidence, 1e-6)
        assertEquals(0.5, merged[1].confidence, 1e-6)
    }

    @Test
    fun mergeKeepsDistinctFacesAndStableTieOrder() {
        val first = YuNetPass(640, 640, 15, listOf(row(0f, 0f, 128f, 128f, 0.8f)))
        val second = YuNetPass(640, 640, 15, listOf(row(384f, 384f, 128f, 128f, 0.8f)))
        val merged = YuNetFacePostProcessor.merge(listOf(first, second), dedupIou = 0.25)
        assertEquals(2, merged.size)
        assertEquals(0.0, merged[0].box.x, 1e-6)
        assertEquals(0.6, merged[1].box.x, 1e-6)
    }

    @Test
    fun openCvRuntimeReleasesReshapeHeader() {
        val source = moduleFile(
            "src/main/kotlin/dev/hryshyn/remanence/ui/collage/analysis/OpenCvYuNetRuntime.kt",
        ).readText().replace(Regex("\\s+"), " ")
        assertTrue(
            "reshape header must be released in finally after the bounded copy",
            source.contains("val flat = faces.reshape(1, 1) try { flat.get(0, 0, values) } finally { flat.release() }"),
        )
        assertTrue(
            "detector input must be the letterboxed BGR image",
            source.contains("detector.detect(resized, faces)"),
        )
    }

    private fun row(x: Float, y: Float, w: Float, h: Float, score: Float): FloatArray {
        val values = FloatArray(15)
        values[0] = x
        values[1] = y
        values[2] = w
        values[3] = h
        values[14] = score
        return values
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    private fun moduleFile(relative: String): File {
        val prefixes = listOf("", "app/", "android/app/")
        var directory: File? = File("").absoluteFile
        repeat(6) {
            val base = directory
            if (base != null) {
                for (prefix in prefixes) {
                    val candidate = File(base, prefix + relative)
                    if (candidate.exists()) return candidate
                }
            }
            directory = directory?.parentFile
        }
        return File(relative)
    }
}
