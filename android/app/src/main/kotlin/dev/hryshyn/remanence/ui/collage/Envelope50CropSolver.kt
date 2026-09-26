package dev.hryshyn.remanence.ui.collage

import dev.hryshyn.remanence.core.model.GeneratorExpression
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Normalized source window in `[0,1]` (the freeze `crop.rect`). */
data class NormalizedCrop(val x: Double, val y: Double, val width: Double, val height: Double)

/**
 * Quantize a normalized crop to the sealed 0..10000 `CropWindow` integers,
 * clamping the right/bottom edge so `x + width <= 10000` (and y/height).
 */
fun NormalizedCrop.toCropWindow(): GeneratorExpression.CropWindow {
    fun quantize(value: Double): Int = Math.round(value * 10_000.0).toInt().coerceIn(0, 10_000)
    val left = quantize(x)
    val top = quantize(y)
    var right = quantize(width)
    var bottom = quantize(height)
    if (left + right > 10_000) right = 10_000 - left
    if (top + bottom > 10_000) bottom = 10_000 - top
    return GeneratorExpression.CropWindow(left, top, right, bottom)
}

/** Normalized box in `[0,1]`. */
data class NormBox(val x: Double, val y: Double, val width: Double, val height: Double)

data class AnalysisFace(val box: NormBox, val confidence: Double)

data class AnalysisObject(val box: NormBox, val confidence: Double, val classId: Int)

data class AnalysisProtectedRegion(
    val kind: String,
    val box: NormBox,
    val confidence: Double,
    val critical: Boolean,
)

/**
 * Immutable normalized `import-field-3.3` analysis inputs (model-independent).
 * The six maps are `gridSize * gridSize` normalized fields; the solver treats
 * them read-only.
 */
class CollageAnalysis(
    val width: Int,
    val height: Int,
    val faces: List<AnalysisFace>,
    val objects: List<AnalysisObject>,
    val protectedRegions: List<AnalysisProtectedRegion>,
    val importance: DoubleArray,
    val baselineField: DoubleArray,
    val objectField: DoubleArray,
    val saliency: DoubleArray,
    val edgeEnergy: DoubleArray,
    val structureField: DoubleArray,
    val gridSize: Int = GRID_SIZE,
) {
    init {
        val cells = gridSize * gridSize
        for ((name, values) in mapOf(
            "importance" to importance,
            "baselineField" to baselineField,
            "objectField" to objectField,
            "saliency" to saliency,
            "edgeEnergy" to edgeEnergy,
            "structureField" to structureField,
        )) {
            require(values.size == cells) { "$name must be $cells values, got ${values.size}" }
        }
        require(width > 0 && height > 0) { "source dimensions must be positive" }
        require(gridSize > 0)
    }

    companion object {
        const val GRID_SIZE = 48
    }
}

/**
 * Deterministic, model-independent port of the freeze V5 crop stage
 * (`runtime/crop.js` base + `runtime/crop-experiment.js` `envelope-50`,
 * evaluated with `runtime/safety.js`). No OpenCV, no inference.
 *
 * `solve` returns the exact normalized `crop.rect` or `null` (fail-closed: no
 * safe base window). The envelope-50 shift falls back to the already-safe base
 * window exactly as the freeze `predict` does; there is no synthetic
 * center-crop fallback when analysis is absent (callers pass no analysis).
 */
object Envelope50CropSolver {

    /** Slot geometry a placement needs a crop for. */
    data class Slot(
        val width: Double,
        val height: Double,
        val shape: String = "rect",
        val zooms: List<Double> = listOf(1.0),
    )

    fun solve(analysis: CollageAnalysis, slot: Slot): NormalizedCrop? {
        val maps = AnalysisMaps(analysis)
        val base = baseCrop(analysis, maps, slot) ?: return null
        return envelope50(analysis, maps, slot, base)
    }

    /** Deterministic base safe window (`crop.js`). */
    private fun baseCrop(analysis: CollageAnalysis, maps: AnalysisMaps, slot: Slot): NormBox? {
        val shape = slot.shape
        val ratio = slot.width / slot.height
        val ar = analysis.width.toDouble() / analysis.height
        val bw = min(1.0, ratio / ar)
        val bh = min(1.0, ar / ratio)
        var best: Pair<NormBox, Double>? = null
        for (zoom in slot.zooms.ifEmpty { listOf(1.0) }) {
            val cw = bw / zoom
            val ch = bh / zoom
            val centers = mutableListOf(
                Pair(0.5, 0.5),
                Pair(1.0 / 3, 1.0 / 3),
                Pair(2.0 / 3, 2.0 / 3),
                Pair(1.0 / 3, 2.0 / 3),
                Pair(2.0 / 3, 1.0 / 3),
            )
            for (face in analysis.faces) centers += Pair(face.box.x + face.box.width / 2, face.box.y + face.box.height / 2)
            for (obj in analysis.objects) centers += Pair(obj.box.x + obj.box.width / 2, obj.box.y + obj.box.height / 2)

            val points = ArrayList<Pair<Double, Double>>()
            for (i in 0..16) points += Pair((1 - cw) * i / 16, (1 - ch) * i / 16)
            for ((cx, cy) in centers) {
                points += Pair(clamp(cx - cw / 2, 0.0, 1 - cw), clamp(cy - ch / 2, 0.0, 1 - ch))
            }
            if (cw < 0.99 && ch < 0.99) {
                for (y in 0..4) for (x in 0..4) {
                    points += Pair((1 - cw) * x / 4, (1 - ch) * y / 4)
                }
            }

            val seen = HashSet<String>()
            var bestThis: Pair<NormBox, Double>? = null
            for ((x, y) in points) {
                val key = String.format(Locale.ROOT, "%.4f:%.4f", x, y)
                if (!seen.add(key)) continue
                val rect = NormBox(x, y, cw, ch)
                val evaluation = evaluate(analysis, maps, slot, rect, shape) ?: continue
                val score = evaluation.coverage.importance * 0.52 +
                    evaluation.coverage.saliency * 0.18 +
                    evaluation.coverage.structure * 0.12 +
                    evaluation.objectKeep * 0.18 -
                    0.012 * (abs(x + cw / 2 - 0.5) + abs(y + ch / 2 - 0.5))
                val previous = bestThis
                if (previous == null || score > previous.second) {
                    bestThis = rect to score
                }
            }
            if (bestThis != null) {
                best = bestThis
                break
            }
        }
        return best?.first
    }

    /** Envelope-50 shift + safety re-check (`crop-experiment.js` predict). */
    private fun envelope50(
        analysis: CollageAnalysis,
        maps: AnalysisMaps,
        slot: Slot,
        base: NormBox,
    ): NormalizedCrop {
        val (anchorX, anchorY) = anchor(analysis)
        val targetX = clamp(anchorX - base.width / 2, 0.0, 1 - base.width)
        val targetY = clamp(anchorY - base.height / 2, 0.0, 1 - base.height)
        val x = base.x + (targetX - base.x) * ENVELOPE_STRENGTH
        val y = base.y + (targetY - base.y) * ENVELOPE_STRENGTH
        val shifted = NormBox(x, y, base.width, base.height)
        val safe = evaluate(analysis, maps, slot, shifted, slot.shape) != null
        return if (safe) {
            NormalizedCrop(x, y, base.width, base.height)
        } else {
            NormalizedCrop(base.x, base.y, base.width, base.height)
        }
    }

    private fun anchor(analysis: CollageAnalysis): Pair<Double, Double> {
        val faces = analysis.faces.filter { it.box.width * it.box.height > 0.003 }
        val objects = analysis.objects.filter {
            it.confidence >= 0.6 && it.box.width * it.box.height > 0.04 && it.classId !in CONTEXT_CLASS_IDS
        }
        val boxes = if (faces.isNotEmpty()) faces.map { it.box } else objects.map { it.box }
        if (boxes.isEmpty()) return 0.5 to 0.5
        val left = boxes.minOf { it.x }
        val top = boxes.minOf { it.y }
        val right = boxes.maxOf { it.x + it.width }
        val bottom = boxes.maxOf { it.y + it.height }
        return ((left + right) / 2) to ((top + bottom) / 2)
    }

    private data class Evaluation(val coverage: Coverage, val objectKeep: Double)

    /** Exact safety evaluation for one window; null = rejected. */
    private fun evaluate(
        analysis: CollageAnalysis,
        maps: AnalysisMaps,
        slot: Slot,
        rect: NormBox,
        shape: String,
    ): Evaluation? {
        val faces = analysis.faces
        val retained = rect.width * rect.height
        if (retained < 0.25) return null
        if (!faces.all { inside(it.box, rect, shape, 0.12) }) return null

        val important = analysis.objects.filter {
            it.confidence >= 0.55 && it.box.width * it.box.height >= 0.015 && !isContextual(it)
        }
        val closeFaces = faces.filter { it.box.width * it.box.height > 0.02 }
        if (closeFaces.size >= 2) {
            val people = important.filter {
                it.classId == 0 && it.confidence > 0.65 && it.box.width * it.box.height > 0.18
            }
            val loses = people.any { o ->
                val overlap = max(0.0, min(o.box.x + o.box.width, rect.x + rect.width) - max(o.box.x, rect.x))
                overlap / o.box.width < 0.96 || rect.y > o.box.y + 0.05
            }
            if (loses) return null
        }

        val objectKeep = if (important.isEmpty()) 1.0 else important.minOf { regionCoverage(it.box, rect, shape) }
        val destructive = important.any { o ->
            val coverage = regionCoverage(o.box, rect, shape)
            val threshold = if (o.classId == 0) {
                if (o.box.width * o.box.height < 0.2) 0.83 else 0.62
            } else if (isBoundedObject(o)) {
                0.82
            } else {
                0.67
            }
            coverage < threshold
        }
        if (destructive) return null

        if (faces.size == 1 && important.any {
                it.classId == 0 && it.confidence >= 0.60 &&
                    it.box.width * it.box.height < 0.2 && !inside(it.box, rect, shape, 0.015)
            }
        ) {
            return null
        }

        val tips = analysis.protectedRegions.filter { it.kind == "structure-tip" && it.critical }
        if (tips.any { regionCoverage(it.box, rect, shape) < 0.65 }) return null

        val coverage = coverage(analysis, maps, rect, shape)
        if (coverage.importance < (if (shape == "ellipse") 0.57 else 0.60)) return null
        if (coverage.edge < 0.48) return null
        if (coverage.saliency < 0.40) return null

        for (half in HALVES) {
            val mass = coverage(analysis, maps, half, "rect").baseline
            if (mass < 0.18) continue
            val ix = max(rect.x, half.x)
            val iy = max(rect.y, half.y)
            val iw = max(0.0, min(rect.x + rect.width, half.x + half.width) - ix)
            val ih = max(0.0, min(rect.y + rect.height, half.y + half.height) - iy)
            val retainedMass = if (iw != 0.0 && ih != 0.0) {
                coverage(analysis, maps, NormBox(ix, iy, iw, ih), "rect").baseline
            } else {
                0.0
            }
            if (retainedMass / mass < 0.32) return null
        }

        val resolution = min(
            analysis.width * rect.width / slot.width,
            analysis.height * rect.height / slot.height,
        )
        if (resolution < 1) return null

        return Evaluation(coverage, objectKeep)
    }

    private fun isContextual(obj: AnalysisObject): Boolean =
        obj.classId in CONTEXT_CLASS_IDS && obj.box.width * obj.box.height > 0.45

    private fun isBoundedObject(obj: AnalysisObject): Boolean =
        obj.box.width * obj.box.height > 0.25 && obj.classId in BOUNDED_OBJECT_CLASS_IDS

    private fun inside(box: NormBox, rect: NormBox, shape: String, pad: Double): Boolean {
        val corners = listOf(
            Pair(box.x - box.width * pad, box.y - box.height * pad),
            Pair(box.x + box.width * (1 + pad), box.y - box.height * pad),
            Pair(box.x + box.width * (1 + pad), box.y + box.height * (1 + pad)),
            Pair(box.x - box.width * pad, box.y + box.height * (1 + pad)),
        )
        return corners.all { (rawX, rawY) ->
            val x = clamp(rawX, 0.0, 1.0)
            val y = clamp(rawY, 0.0, 1.0)
            x >= rect.x - 1e-5 && x <= rect.x + rect.width + 1e-5 &&
                y >= rect.y - 1e-5 && y <= rect.y + rect.height + 1e-5 &&
                maskPoint((x - rect.x) / rect.width, (y - rect.y) / rect.height, shape)
        }
    }

    private fun regionCoverage(box: NormBox, rect: NormBox, shape: String): Double {
        if (shape == "rect") {
            val w = max(0.0, min(box.x + box.width, rect.x + rect.width) - max(box.x, rect.x))
            val h = max(0.0, min(box.y + box.height, rect.y + rect.height) - max(box.y, rect.y))
            return w * h / (box.width * box.height)
        }
        var count = 0
        for (y in 0 until 12) for (x in 0 until 12) {
            val px = box.x + box.width * (x + 0.5) / 12
            val py = box.y + box.height * (y + 0.5) / 12
            if (px >= rect.x && px <= rect.x + rect.width && py >= rect.y && py <= rect.y + rect.height &&
                maskPoint((px - rect.x) / rect.width, (py - rect.y) / rect.height, shape)
            ) {
                count++
            }
        }
        return count / 144.0
    }

    private fun coverage(
        analysis: CollageAnalysis,
        maps: AnalysisMaps,
        rect: NormBox,
        shape: String,
    ): Coverage {
        val n = analysis.gridSize
        val x0 = clamp(Math.ceil(rect.x * n - 0.5), 0.0, n.toDouble()).toInt()
        val y0 = clamp(Math.ceil(rect.y * n - 0.5), 0.0, n.toDouble()).toInt()
        val x1 = clamp(Math.floor((rect.x + rect.width) * n - 0.5) + 1.0, 0.0, n.toDouble()).toInt()
        val y1 = clamp(Math.floor((rect.y + rect.height) * n - 0.5) + 1.0, 0.0, n.toDouble()).toInt()
        var visible = 0
        val sums = HashMap<String, Double>()
        if (shape == "rect") {
            for ((name, map) in maps.byName) {
                val s = n + 1
                val value = if (map.total != 0.0) {
                    (map.integral[y1 * s + x1] - map.integral[y0 * s + x1] -
                        map.integral[y1 * s + x0] + map.integral[y0 * s + x0]) / map.total
                } else {
                    1.0
                }
                sums[name] = value
            }
            visible = (x1 - x0) * (y1 - y0)
        } else {
            for (name in maps.byName.keys) sums[name] = 0.0
            for (y in y0 until y1) for (x in x0 until x1) {
                if (maskPoint(((x + 0.5) / n - rect.x) / rect.width, ((y + 0.5) / n - rect.y) / rect.height, shape)) {
                    visible++
                    for ((name, map) in maps.byName) sums[name] = sums.getValue(name) + map.values[y * n + x]
                }
            }
            for ((name, map) in maps.byName) {
                sums[name] = if (map.total != 0.0) sums.getValue(name) / map.total else 1.0
            }
        }
        return Coverage(
            importance = sums.getValue("importance"),
            edge = sums.getValue("edgeEnergy"),
            saliency = sums.getValue("saliency"),
            structure = sums.getValue("structureField"),
            baseline = sums.getValue("baselineField"),
            objects = sums.getValue("objectField"),
            visibleArea = visible.toDouble() / (n * n),
        )
    }

    private fun maskPoint(x: Double, y: Double, shape: String): Boolean = when (shape) {
        "ellipse" -> ((x - 0.5) / 0.5) * ((x - 0.5) / 0.5) + ((y - 0.5) / 0.5) * ((y - 0.5) / 0.5) <= 1
        "round" -> {
            val r = 0.12
            val cx = clamp(x, r, 1 - r)
            val cy = clamp(y, r, 1 - r)
            (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r + 1e-6
        }
        else -> true
    }

    private data class Coverage(
        val importance: Double,
        val edge: Double,
        val saliency: Double,
        val structure: Double,
        val baseline: Double,
        val objects: Double,
        val visibleArea: Double,
    )

    private class AnalysisMaps(analysis: CollageAnalysis) {
        val byName: Map<String, MapData> = mapOf(
            "importance" to MapData.of(analysis.gridSize, analysis.importance),
            "edgeEnergy" to MapData.of(analysis.gridSize, analysis.edgeEnergy),
            "saliency" to MapData.of(analysis.gridSize, analysis.saliency),
            "structureField" to MapData.of(analysis.gridSize, analysis.structureField),
            "baselineField" to MapData.of(analysis.gridSize, analysis.baselineField),
            "objectField" to MapData.of(analysis.gridSize, analysis.objectField),
        )
    }

    private class MapData(val values: DoubleArray, val integral: DoubleArray, val total: Double) {
        companion object {
            fun of(gridSize: Int, values: DoubleArray): MapData {
                val s = gridSize + 1
                val integral = DoubleArray(s * s)
                var total = 0.0
                for (y in 0 until gridSize) for (x in 0 until gridSize) {
                    val v = values[y * gridSize + x]
                    total += v
                    integral[(y + 1) * s + x + 1] =
                        v + integral[y * s + x + 1] + integral[(y + 1) * s + x] - integral[y * s + x]
                }
                return MapData(values, integral, total)
            }
        }
    }

    private fun clamp(value: Double, low: Double, high: Double): Double = max(low, min(high, value))

    private const val ENVELOPE_STRENGTH = 0.5
    private val CONTEXT_CLASS_IDS = setOf(56, 57, 60)
    private val BOUNDED_OBJECT_CLASS_IDS = setOf(
        39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 73, 74, 75,
    )
    private val HALVES = listOf(
        NormBox(0.0, 0.0, 1.0, 0.5),
        NormBox(0.0, 0.5, 1.0, 0.5),
        NormBox(0.0, 0.0, 0.5, 1.0),
        NormBox(0.5, 0.0, 0.5, 1.0),
    )
}
