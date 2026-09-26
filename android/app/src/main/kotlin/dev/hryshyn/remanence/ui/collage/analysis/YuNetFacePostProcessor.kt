package dev.hryshyn.remanence.ui.collage.analysis

import dev.hryshyn.remanence.ui.collage.AnalysisFace
import dev.hryshyn.remanence.ui.collage.NanoDetDecoder
import dev.hryshyn.remanence.ui.collage.NormBox

/**
 * Frozen YuNet configuration. Defaults are the exact freeze contract:
 * `score=0.65`, NMS `0.3`, `topK=5000`, two long-edge passes 640 then 960, and
 * the authoritative dedup IoU `0.25`.
 */
data class YuNetConfig(
    val longEdges: List<Int> = listOf(640, 960),
    val scoreThreshold: Float = 0.65f,
    val nmsThreshold: Float = 0.3f,
    val topK: Int = 5000,
    val dedupIou: Double = 0.25,
) {
    init {
        require(longEdges.isNotEmpty()) { "at least one long-edge pass is required" }
        require(longEdges.all { it > 0 }) { "long edges must be positive" }
    }
}

/**
 * One detector pass: the letterboxed input size, output column count, and each
 * finite row. Rows hold `x, y, w, h`, five `x, y` landmarks, then the score.
 */
data class YuNetPass(
    val passWidth: Int,
    val passHeight: Int,
    val columns: Int,
    val rows: List<FloatArray>,
)

/**
 * Pure YuNet post-processing transcribed from the frozen decoder. No OpenCV, so
 * it is exhaustively unit-testable on the JVM. Faces are inverse-mapped to the
 * upright source, clamped, degenerate boxes are dropped, and cross-pass
 * duplicates are removed greedily in stable confidence-descending order.
 */
object YuNetFacePostProcessor {

    /** `x, y, w, h` + 5 `(x, y)` landmarks + score. */
    const val MIN_COLUMNS = 15

    /** Reject boxes whose normalized width or height does not exceed this. */
    const val MIN_BOX_FRACTION = 0.01f

    private const val LANDMARK_FIRST = 4
    private const val LANDMARK_COUNT = 5

    /** Python half-even resize: `round(dim * longEdge / max(w, h))`. */
    fun letterboxSize(sourceWidth: Int, sourceHeight: Int, longEdge: Int): Pair<Int, Int> {
        require(sourceWidth > 0 && sourceHeight > 0) { "source dimensions must be positive" }
        require(longEdge > 0) { "long edge must be positive" }
        val k = longEdge.toDouble() / maxOf(sourceWidth, sourceHeight)
        return NanoDetDecoder.halfEvenRound(sourceWidth * k) to NanoDetDecoder.halfEvenRound(sourceHeight * k)
    }

    /** Map one pass's rows to immutable normalized faces, dropping degenerates. */
    fun normalize(pass: YuNetPass): List<AnalysisFace> {
        if (pass.rows.isEmpty()) return emptyList()
        require(pass.columns >= MIN_COLUMNS) {
            "YuNet rows require at least $MIN_COLUMNS columns"
        }
        require(pass.passWidth > 0 && pass.passHeight > 0) { "pass dimensions must be positive" }
        val out = ArrayList<AnalysisFace>(pass.rows.size)
        for (row in pass.rows) {
            require(row.size >= pass.columns) { "short YuNet row" }
            for (column in 0 until pass.columns) {
                require(row[column].isFinite()) { "non-finite YuNet row" }
            }
            for (landmark in 0 until LANDMARK_COUNT) {
                val base = LANDMARK_FIRST + landmark * 2
                require(base + 1 < pass.columns) { "landmark columns missing" }
            }
            val x0 = (row[0] / pass.passWidth).coerceIn(0f, 1f)
            val y0 = (row[1] / pass.passHeight).coerceIn(0f, 1f)
            val x1 = ((row[0] + row[2]) / pass.passWidth).coerceIn(0f, 1f)
            val y1 = ((row[1] + row[3]) / pass.passHeight).coerceIn(0f, 1f)
            val width = x1 - x0
            val height = y1 - y0
            if (width <= MIN_BOX_FRACTION || height <= MIN_BOX_FRACTION) continue
            out += AnalysisFace(
                box = NormBox(x0.toDouble(), y0.toDouble(), width.toDouble(), height.toDouble()),
                confidence = row[pass.columns - 1].toDouble(),
            )
        }
        return out
    }

    /**
     * Deduplicate across passes: stable confidence-descending order, then keep a
     * face unless an already-kept face overlaps it with IoU greater than
     * [dedupIou]. Ties keep the earlier (first-pass) geometry by construction.
     */
    fun merge(passes: List<YuNetPass>, dedupIou: Double = 0.25): List<AnalysisFace> {
        val found = ArrayList<AnalysisFace>()
        for (pass in passes) found += normalize(pass)
        val sorted = found
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<AnalysisFace>> { it.value.confidence }
                    .thenBy { it.index },
            )
            .map { it.value }

        val kept = ArrayList<AnalysisFace>(sorted.size)
        val suppressed = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (suppressed[i]) continue
            kept += sorted[i]
            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                if (iou(sorted[i], sorted[j]) > dedupIou) suppressed[j] = true
            }
        }
        return kept
    }

    private fun iou(a: AnalysisFace, b: AnalysisFace): Double {
        val ax2 = a.box.x + a.box.width
        val ay2 = a.box.y + a.box.height
        val bx2 = b.box.x + b.box.width
        val by2 = b.box.y + b.box.height
        val interWidth = minOf(ax2, bx2) - maxOf(a.box.x, b.box.x)
        val interHeight = minOf(ay2, by2) - maxOf(a.box.y, b.box.y)
        val inter = if (interWidth > 0 && interHeight > 0) interWidth * interHeight else 0.0
        val union = a.box.width * a.box.height + b.box.width * b.box.height - inter
        return inter / maxOf(1e-9, union)
    }
}
