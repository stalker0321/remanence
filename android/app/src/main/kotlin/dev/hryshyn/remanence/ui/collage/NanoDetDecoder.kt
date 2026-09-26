package dev.hryshyn.remanence.ui.collage

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * One named NanoDet output tensor head. `values` is read-only by contract and
 * is never mutated by the decoder.
 */
class NanoDetHead(
    val name: String,
    val shape: IntArray,
    val values: FloatArray,
) {
    override fun toString(): String = "NanoDetHead($name, ${shape.toList()})"
}

/**
 * Pure, model-independent port of the frozen `runtime/models/nanodet.py`
 * `post_process` plus the `import-field-3.3` inverse letterbox mapping
 * (`runtime/tools/legacy_features.py`). No OpenCV, no Dnn, no model load.
 *
 * It accepts primitive source geometry and injected named float tensor heads
 * (`FloatArray` + shape) that a future OpenCV forward pass fills, and rebuilds:
 * the 416 letterbox scale/pad mapping, head pairing by spatial count (cls 80ch,
 * reg 32ch) in stride order 8/16/32/64, per-anchor DFL (`reg_max=7`) box decode
 * with inverse mapping and clamping, the class threshold, bounded top-k and a
 * class-agnostic NMS with stable score/index tie-break, emitting immutable
 * normalized [AnalysisObject]s.
 *
 * Parity note: the frozen `nanodet.py` treats the class logits as already
 * sigmoid-fused (it applies `max`/`argmax` directly, no extra sigmoid); this
 * port matches that and validates every consumed value as finite.
 *
 * Oracle note: numpy/cv2 are not installed on this host and downloads are
 * disallowed, so the frozen module cannot be imported at test time. Fixtures
 * were produced by a line-for-line pure-Python transcription of
 * `nanodet.py#post_process` + the legacy inverse mapping + OpenCV `NMSFast_`
 * (see `NanoDetDecoderTest` KDoc); the real ONNX forward + cv2 parity remains
 * the next (inference) step.
 */
object NanoDetDecoder {

    const val INPUT_SIZE = 416
    const val CLASS_COUNT = 80
    const val REG_CHANNELS = 32
    const val REG_MAX = 7
    const val PROB_THRESHOLD = 0.35f
    const val IOU_THRESHOLD = 0.55f
    const val NMS_PRE = 1000
    const val MIN_BOX_FRACTION = 0.01f

    private val SIDE_TO_STRIDE = mapOf(52 to 8, 26 to 16, 13 to 32, 6 to 64)
    private val PROJECT: FloatArray = FloatArray(REG_MAX + 1) { it.toFloat() }

    data class Letterbox(
        val sourceWidth: Int,
        val sourceHeight: Int,
        val rw: Int,
        val rh: Int,
        val padX: Int,
        val padY: Int,
        val scale: Double,
    )

    sealed interface Result {
        data class Detections(val objects: List<AnalysisObject>) : Result
        data class Invalid(val reason: String) : Result
    }

    /** A pre-NMS candidate box in the 416 input space. */
    internal data class Candidate(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val score: Float,
        val classId: Int,
    )

    /** Exact frozen letterbox: `k = 416/max(w,h)`, centered pad, fill 114. */
    fun letterbox(sourceWidth: Int, sourceHeight: Int): Letterbox {
        require(sourceWidth > 0 && sourceHeight > 0) { "source dimensions must be positive" }
        val k = INPUT_SIZE.toDouble() / max(sourceWidth, sourceHeight)
        // Python `round` is round-half-to-even; Kotlin `Math.round` is half-up.
        val rw = halfEvenRound(sourceWidth * k)
        val rh = halfEvenRound(sourceHeight * k)
        return Letterbox(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            rw = rw,
            rh = rh,
            padX = (INPUT_SIZE - rw) / 2,
            padY = (INPUT_SIZE - rh) / 2,
            scale = k,
        )
    }

    fun decode(heads: List<NanoDetHead>, letterbox: Letterbox): Result {
        val candidates = decodeCandidates(heads) ?: return Result.Invalid("malformed NanoDet heads")
        val kept = nonMaxSuppression(candidates)
        val objects = ArrayList<AnalysisObject>(kept.size)
        for (candidate in kept) {
            val x = ((candidate.x1 - letterbox.padX) / letterbox.rw).coerceIn(0f, 1f)
            val y = ((candidate.y1 - letterbox.padY) / letterbox.rh).coerceIn(0f, 1f)
            val right = ((candidate.x2 - letterbox.padX) / letterbox.rw).coerceIn(0f, 1f)
            val bottom = ((candidate.y2 - letterbox.padY) / letterbox.rh).coerceIn(0f, 1f)
            val width = right - x
            val height = bottom - y
            if (width > MIN_BOX_FRACTION && height > MIN_BOX_FRACTION) {
                objects += AnalysisObject(
                    box = NormBox(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble()),
                    confidence = candidate.score.toDouble(),
                    classId = candidate.classId,
                )
            }
        }
        return Result.Detections(objects)
    }

    /** Decoded candidates before NMS, or null when the heads are malformed. */
    internal fun decodeCandidates(heads: List<NanoDetHead>): List<Candidate>? {
        if (heads.isEmpty()) return null
        val cls = ArrayList<Triple<Int, NanoDetHead, Int>>() // spatial, head, side
        val reg = HashMap<Int, NanoDetHead>()
        for (head in heads) {
            if (head.shape.size !in 2..3) return null
            if (head.shape.size == 3 && head.shape[0] != 1) return null
            val expected = head.shape.fold(1L) { acc, v -> acc * v }
            if (expected != head.values.size.toLong()) return null
            val last = head.shape[head.shape.size - 1]
            val spatial = head.shape[head.shape.size - 2]
            if (spatial <= 0) return null
            when (last) {
                CLASS_COUNT -> {
                    val side = perfectSide(spatial) ?: return null
                    if (side !in SIDE_TO_STRIDE) return null
                    cls += Triple(spatial, head, side)
                }
                REG_CHANNELS -> {
                    if (perfectSide(spatial) !in SIDE_TO_STRIDE) return null
                    if (reg.put(spatial, head) != null) return null
                }
                else -> return null
            }
        }
        if (cls.isEmpty() || reg.size != cls.size) return null

        val ordered = cls.sortedWith(compareByDescending { it.first })
        val candidates = ArrayList<Candidate>()
        for ((spatial, clsHead, side) in ordered) {
            val regHead = reg[spatial] ?: return null
            val stride = SIDE_TO_STRIDE.getValue(side)
            // Batch dim is validated to be exactly 1, so both 2-D and [1,N,C]
            // tensors are contiguous N*C with no batch offset.
            val batchOffsetCls = 0
            val batchOffsetReg = 0

            // Bounded per-level top-k by max class score (desc score, asc index).
            val anchors = spatial
            val selected: List<Int> = if (anchors > NMS_PRE) {
                (0 until anchors)
                    .map { it to clsMax(clsHead.values, batchOffsetCls, it) }
                    .sortedWith(compareByDescending<Pair<Int, Float>> { it.second }.thenBy { it.first })
                    .take(NMS_PRE)
                    .map { it.first }
            } else {
                (0 until anchors).toList()
            }

            for (anchor in selected) {
                val maxScore = clsMax(clsHead.values, batchOffsetCls, anchor)
                if (!maxScore.isFinite()) return null
                var classId = 0
                var best = Float.NEGATIVE_INFINITY
                for (c in 0 until CLASS_COUNT) {
                    val value = clsHead.values[batchOffsetCls + anchor * CLASS_COUNT + c]
                    if (!value.isFinite()) return null
                    if (value > best) {
                        best = value
                        classId = c
                    }
                }
                val row = batchOffsetReg + anchor * REG_CHANNELS
                val d0 = dfl(regHead.values, row) ?: return null
                val d1 = dfl(regHead.values, row + 8) ?: return null
                val d2 = dfl(regHead.values, row + 16) ?: return null
                val d3 = dfl(regHead.values, row + 24) ?: return null
                val col = anchor % side
                val line = anchor / side
                val cx = col * stride + 0.5f * (stride - 1)
                val cy = line * stride + 0.5f * (stride - 1)
                candidates += Candidate(
                    x1 = (cx - d0 * stride).coerceIn(0f, INPUT_SIZE.toFloat()),
                    y1 = (cy - d1 * stride).coerceIn(0f, INPUT_SIZE.toFloat()),
                    x2 = (cx + d2 * stride).coerceIn(0f, INPUT_SIZE.toFloat()),
                    y2 = (cy + d3 * stride).coerceIn(0f, INPUT_SIZE.toFloat()),
                    score = maxScore,
                    classId = classId,
                )
            }
        }
        return candidates
    }

    /** Class-agnostic NMS with stable (score desc, index asc) tie-break. */
    internal fun nonMaxSuppression(candidates: List<Candidate>): List<Candidate> {
        val order = candidates.indices.sortedWith(
            compareByDescending<Int> { candidates[it].score }.thenBy { it },
        )
        val removed = BooleanArray(candidates.size)
        val kept = ArrayList<Candidate>()
        for (i in order) {
            if (removed[i]) continue
            // Freeze/OpenCV acceptance is exclusive: score == threshold is rejected.
            if (candidates[i].score <= PROB_THRESHOLD) continue
            kept += candidates[i]
            for (j in order) {
                if (j == i || removed[j]) continue
                if (iou(candidates[i], candidates[j]) > IOU_THRESHOLD) removed[j] = true
            }
        }
        return kept
    }

    private fun clsMax(values: FloatArray, batchOffset: Int, anchor: Int): Float {
        var best = Float.NEGATIVE_INFINITY
        for (c in 0 until CLASS_COUNT) best = max(best, values[batchOffset + anchor * CLASS_COUNT + c])
        return best
    }

    /** DFL softmax over 8 channels, dot [0..7]; null on non-finite input. */
    private fun dfl(values: FloatArray, offset: Int): Float? {
        var maxValue = Float.NEGATIVE_INFINITY
        for (p in 0..REG_MAX) {
            val raw = values[offset + p]
            if (!raw.isFinite()) return null
            if (raw > maxValue) maxValue = raw
        }
        var sum = 0f
        val exps = FloatArray(REG_MAX + 1)
        for (p in 0..REG_MAX) {
            val e = exp((values[offset + p] - maxValue).toDouble()).toFloat()
            exps[p] = e
            sum += e
        }
        if (sum <= 0f || !sum.isFinite()) return null
        var dot = 0f
        for (p in 0..REG_MAX) dot += exps[p] / sum * PROJECT[p]
        return dot
    }

    private fun iou(a: Candidate, b: Candidate): Float {
        val ix1 = max(a.x1, b.x1)
        val iy1 = max(a.y1, b.y1)
        val ix2 = min(a.x2, b.x2)
        val iy2 = min(a.y2, b.y2)
        val iw = max(0f, ix2 - ix1)
        val ih = max(0f, iy2 - iy1)
        val inter = iw * ih
        val union = (a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - inter
        return if (union > 0f) inter / union else 0f
    }

    /**
     * Deterministic round-half-to-even for finite nonnegative values, matching
     * Python `round` used by the frozen letterbox resize computation.
     */
    internal fun halfEvenRound(value: Double): Int {
        require(value.isFinite() && value >= 0.0) {
            "half-even rounding requires a finite nonnegative value"
        }
        val lower = floor(value)
        val fraction = value - lower
        val rounded = when {
            fraction < 0.5 -> lower
            fraction > 0.5 -> lower + 1.0
            else -> if (lower % 2.0 == 0.0) lower else lower + 1.0
        }
        return rounded.toInt()
    }

    private fun perfectSide(spatial: Int): Int? {
        var side = 1
        while (side * side < spatial) side++
        return if (side * side == spatial) side else null
    }
}
