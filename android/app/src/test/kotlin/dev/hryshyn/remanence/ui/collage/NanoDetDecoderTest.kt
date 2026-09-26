package dev.hryshyn.remanence.ui.collage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity fixtures for [NanoDetDecoder].
 *
 * Oracle provenance: the frozen `runtime/models/nanodet.py` imports numpy/cv2,
 * which are not installed here and cannot be downloaded, so the module is not
 * executed. A line-for-line pure-Python transcription of
 * `nanodet.py#post_process`, the `legacy_features.py` inverse letterbox mapping
 * and OpenCV `dnn::NMSBoxes` (`NMSFast_`) produced the expected values pinned
 * below from synthetic heads built by the same compact spec:
 *
 * - side 6 (spatial 36, stride 64), source 1000x1000 or 800x1000;
 * - one positive anchor per detection with class score and four one-hot DFL
 *   groups (`reg[g*8 + d[g]] = 20`), all other values 0.
 *
 * Declared tolerances: normalized box coordinates 1e-4, confidence 1e-6,
 * classId/order exact. The real ONNX forward pass and cv2 parity is the next
 * (inference) step; this slice is the pure decoder.
 */
class NanoDetDecoderTest {

    private data class Det(val anchor: Int, val classId: Int, val score: Float, val d: List<Int>)

    private fun makeHeads(detections: List<Det>, side: Int, batch: Boolean = false): List<NanoDetHead> {
        val n = side * side
        val cls = FloatArray(n * NanoDetDecoder.CLASS_COUNT)
        val reg = FloatArray(n * NanoDetDecoder.REG_CHANNELS)
        for (det in detections) {
            cls[det.anchor * NanoDetDecoder.CLASS_COUNT + det.classId] = det.score
            for (g in 0..3) reg[det.anchor * NanoDetDecoder.REG_CHANNELS + g * 8 + det.d[g]] = 20f
        }
        val clsShape = if (batch) intArrayOf(1, n, NanoDetDecoder.CLASS_COUNT) else intArrayOf(n, NanoDetDecoder.CLASS_COUNT)
        val regShape = if (batch) intArrayOf(1, n, NanoDetDecoder.REG_CHANNELS) else intArrayOf(n, NanoDetDecoder.REG_CHANNELS)
        return listOf(NanoDetHead("cls", clsShape, cls), NanoDetHead("reg", regShape, reg))
    }

    private fun detections(heads: List<NanoDetHead>, w: Int, h: Int): List<AnalysisObject> {
        val result = NanoDetDecoder.decode(heads, NanoDetDecoder.letterbox(w, h))
        assertTrue("expected Detections, got $result", result is NanoDetDecoder.Result.Detections)
        return (result as NanoDetDecoder.Result.Detections).objects
    }

    private fun assertObject(
        o: AnalysisObject,
        x: Double, y: Double, w: Double, h: Double,
        confidence: Double, classId: Int,
    ) {
        assertEquals(x, o.box.x, 1e-4)
        assertEquals(y, o.box.y, 1e-4)
        assertEquals(w, o.box.width, 1e-4)
        assertEquals(h, o.box.height, 1e-4)
        assertEquals(confidence, o.confidence, 1e-6)
        assertEquals(classId, o.classId)
    }

    @Test
    fun letterboxMatchesFrozenMapping() {
        val square = NanoDetDecoder.letterbox(1000, 1000)
        assertEquals(416, square.rw)
        assertEquals(416, square.rh)
        assertEquals(0, square.padX)
        assertEquals(0, square.padY)
        assertEquals(0.416, square.scale, 1e-9)

        val portrait = NanoDetDecoder.letterbox(800, 1000)
        assertEquals(333, portrait.rw)
        assertEquals(416, portrait.rh)
        assertEquals(41, portrait.padX)
        assertEquals(0, portrait.padY)
    }

    @Test
    fun oneDetectionMatchesOracle() {
        val objects = detections(makeHeads(listOf(Det(0, 0, 0.9f, listOf(0, 0, 1, 1))), side = 6), 1000, 1000)
        assertEquals(1, objects.size)
        assertObject(objects.first(), 0.075721, 0.075721, 0.153846, 0.153846, 0.9, 0)
    }

    @Test
    fun edgeClampMatchesOracle() {
        val objects = detections(makeHeads(listOf(Det(0, 3, 0.8f, listOf(1, 1, 1, 1))), side = 6), 1000, 1000)
        assertEquals(1, objects.size)
        assertObject(objects.first(), 0.0, 0.0, 0.229567, 0.229567, 0.8, 3)
    }

    @Test
    fun overlapNmsSuppressesTheLowerScore() {
        val heads = makeHeads(
            listOf(
                Det(0, 0, 0.9f, listOf(0, 0, 1, 1)),
                Det(1, 0, 0.7f, listOf(1, 0, 0, 1)), // fully contained in Det 0 -> suppressed
                Det(7, 5, 0.6f, listOf(0, 0, 1, 1)),
            ),
            side = 6,
        )
        val objects = detections(heads, 1000, 1000)
        assertEquals(2, objects.size)
        assertObject(objects[0], 0.075721, 0.075721, 0.153846, 0.153846, 0.9, 0)
        assertObject(objects[1], 0.229567, 0.229567, 0.153846, 0.153846, 0.6, 5)
    }

    @Test
    fun paddedSourceMapsBackToOriginalSpace() {
        val objects = detections(makeHeads(listOf(Det(0, 0, 0.9f, listOf(0, 0, 1, 1))), side = 6), 800, 1000)
        assertEquals(1, objects.size)
        assertObject(objects.first(), 0.0, 0.075721, 0.163664, 0.153846, 0.9, 0)
    }

    @Test
    fun headOrderAndBatchDimsDoNotChangeTheResult() {
        val normal = detections(makeHeads(listOf(Det(0, 0, 0.9f, listOf(0, 0, 1, 1))), side = 6), 1000, 1000)
        val reversedHeads = makeHeads(listOf(Det(0, 0, 0.9f, listOf(0, 0, 1, 1))), side = 6).reversed()
        val reversed = detections(reversedHeads, 1000, 1000)
        val batched = detections(makeHeads(listOf(Det(0, 0, 0.9f, listOf(0, 0, 1, 1))), side = 6, batch = true), 1000, 1000)
        assertEquals(normal, reversed)
        assertEquals(normal, batched)
    }

    @Test
    fun noDetectionReturnsEmpty() {
        assertTrue(detections(makeHeads(emptyList(), side = 6), 1000, 1000).isEmpty())
    }

    @Test
    fun decodeIsDeterministic() {
        val heads = makeHeads(listOf(Det(0, 0, 0.9f, listOf(0, 0, 1, 1))), side = 6)
        assertEquals(detections(heads, 1000, 1000), detections(heads, 1000, 1000))
    }

    @Test
    fun malformedShapesFailClosed() {
        val good = makeHeads(listOf(Det(0, 0, 0.9f, listOf(0, 0, 1, 1))), side = 6)
        val cls = good.first()
        val reg = good.last()

        assertNull(NanoDetDecoder.decodeCandidates(emptyList()))
        assertNull(
            NanoDetDecoder.decodeCandidates(
                listOf(NanoDetHead("cls", intArrayOf(36, 80), FloatArray(36 * 80 - 1)), reg),
            ),
        )
        assertNull(NanoDetDecoder.decodeCandidates(listOf(NanoDetHead("cls", intArrayOf(36, 64), FloatArray(36 * 64)), reg)))
        assertNull(NanoDetDecoder.decodeCandidates(listOf(NanoDetHead("cls", intArrayOf(25, 80), FloatArray(25 * 80)), NanoDetHead("reg", intArrayOf(25, 32), FloatArray(25 * 32)))))
        assertNull(NanoDetDecoder.decodeCandidates(listOf(cls)))
        assertNull(NanoDetDecoder.decodeCandidates(listOf(NanoDetHead("cls", intArrayOf(2, 36, 80), FloatArray(2 * 36 * 80)), reg)))
        assertNull(
            NanoDetDecoder.decodeCandidates(
                listOf(cls, reg, NanoDetHead("reg2", intArrayOf(36, 32), FloatArray(36 * 32))),
            ),
        )
    }

    @Test
    fun nonFiniteValuesFailClosed() {
        val cls = FloatArray(36 * 80)
        val reg = FloatArray(36 * 32)
        cls[0] = Float.NaN
        val nanResult = NanoDetDecoder.decode(
            listOf(NanoDetHead("cls", intArrayOf(36, 80), cls), NanoDetHead("reg", intArrayOf(36, 32), reg)),
            NanoDetDecoder.letterbox(1000, 1000),
        )
        assertTrue("NaN cls must fail closed, got $nanResult", nanResult is NanoDetDecoder.Result.Invalid)

        val cls2 = FloatArray(36 * 80).also { it[0] = 0.9f }
        val reg2 = FloatArray(36 * 32)
        reg2[0] = Float.POSITIVE_INFINITY
        val infResult = NanoDetDecoder.decode(
            listOf(NanoDetHead("cls", intArrayOf(36, 80), cls2), NanoDetHead("reg", intArrayOf(36, 32), reg2)),
            NanoDetDecoder.letterbox(1000, 1000),
        )
        assertTrue("Inf reg must fail closed, got $infResult", infResult is NanoDetDecoder.Result.Invalid)
    }

    @Test
    fun topKIsBoundedToNmsPre() {
        val side = 52
        val n = side * side
        val cls = FloatArray(n * 80)
        for (anchor in 0 until n) cls[anchor * 80] = 1.0f - anchor * 1e-7f
        val candidates = NanoDetDecoder.decodeCandidates(
            listOf(NanoDetHead("cls", intArrayOf(n, 80), cls), NanoDetHead("reg", intArrayOf(n, 32), FloatArray(n * 32))),
        )
        assertTrue("expected candidates", candidates != null)
        assertEquals(NanoDetDecoder.NMS_PRE, candidates!!.size)
        // Highest score (anchor 0) is kept first.
        assertEquals(1.0f, candidates.first().score, 1e-6f)
        assertEquals(0, candidates.first().classId)
    }

    @Test
    fun decoderDoesNotMutateInputTensors() {
        val heads = makeHeads(listOf(Det(0, 0, 0.9f, listOf(0, 0, 1, 1))), side = 6)
        val snapshots = heads.map { it.values.copyOf() }
        detections(heads, 1000, 1000)
        heads.forEachIndexed { index, head -> assertArrayEquals(snapshots[index], head.values, 0f) }
    }
}
