package dev.hryshyn.remanence.ui.collage

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-JVM input primitives for the frozen `import-field-3.3` field assembly
 * (`runtime/tools/legacy_features.py:74-99`). No `android.*`, no OpenCV.
 *
 * All maps are 48x48 row-major (`y * 48 + x`), already normalized to `[0,1]`
 * by the pixel stage (A4b). `structureField` is the already-merged contour
 * endpoint map: the `np.maximum(tips, gaussian_field(...))` merge
 * (`legacy_features.py:68`) is an explicit A4a/A4b interface decision owned
 * by the pixel loop, so A4a has no morphology.
 *
 * `tipBoxes` are the contour-order, already-clamped structure-tip boxes
 * (`[bx, by, min(0.03, 1-bx), min(0.04, 1-by)]`, `legacy_features.py:66-67`,
 * with the `0.025` proximity dedup of line 69) owned by the pixel loop. A4a
 * owns only the candidate filter (`0.08 < x < 0.90`), the stable `box[1]`
 * ordering, and the critical flags (`legacy_features.py:90-92`). Passing
 * boxes (not a re-thresholded field) is the only freeze-faithful choice:
 * re-deriving endpoints from the merged map would invent behavior.
 *
 * Faces and objects are the already-normalized, deduped detector outputs in
 * detector order. Arrays are read-only by contract and never mutated.
 */
data class ImportFieldPrimitives(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val faces: List<AnalysisFace>,
    val objects: List<AnalysisObject>,
    val saliency: DoubleArray,
    val edgeEnergy: DoubleArray,
    val contrast: DoubleArray,
    val structureField: DoubleArray,
    val tipBoxes: List<NormBox>,
)

/**
 * Pure port of the frozen `import-field-3.3` field assembly
 * (`runtime/tools/legacy_features.py:74-99`, `gaussian_field` lines 17-19,
 * `center` line 73). Produces the existing [CollageAnalysis] consumed by
 * [Envelope50CropSolver]; no new solver-facing type.
 *
 * Pinned contract:
 * - `gaussian_field(box, expand)`: cell `(i, j)` centers
 *   `xx = (i + 0.5) / 48`, `yy = (j + 0.5) / 48`;
 *   `ex = (xx - x - w/2) / max(w * (0.5 + expand), 0.025)` (same for `y`);
 *   `field = exp(-0.5 * ex^2 - 0.5 * ey^2)`.
 * - `center = exp(-((xx - 0.5)^2 + (yy - 0.5)^2) / 0.18)`.
 * - `base = 0.015 + 0.34*sal + 0.25*edge + 0.12*contrast + 0.12*tips
 *   + 0.025*center`.
 * - object field: element-wise `max` of `gaussian(obj.box, 0.06) * confidence`.
 * - face field: element-wise `max` of `gaussian(face.box, 0.25) * confidence`.
 * - face-context soft add, inside the same face loop after the face field:
 *   `b = [max(0, x - 0.7w), max(0, y - 0.5h),
 *   min(1, x + 1.7w) - b0, min(1, y + 3.2h) - b1]`;
 *   `base += gaussian(b, 0.05) * 0.08`; region `face-context`, confidence 0.3.
 * - `importance = base + 0.65*of + 1.2*ff`;
 *   `baselineField = base + 1.2*ff`; `objectField = of`;
 *   `saliency`/`edgeEnergy`/`structureField` pass through.
 * - region layout: structure-tip regions first (contour order), then one
 *   `object` region per object (detector order), then `face` + `face-context`
 *   per face, interleaved per face (detector order).
 * - tip candidates: structure-tip regions with `0.08 < box.x < 0.90`, stable
 *   ascending `box.y`; the first two get `critical = !person_focus`.
 * - `person_focus = faces.size > 5 || any(face.width * face.height > 0.005)`.
 *
 * Float policy: everything accumulates in `Double` (float64, matching numpy
 * once combined with the Python scalar constants). The six output arrays are
 * packed exactly like `np.round(a, 4)`: `Math.rint(value * 10_000.0) / 10_000.0`
 * (multiply, half-even `rint`, divide; never `Math.round`). Faces, objects,
 * and region boxes keep full precision.
 */
object FieldAggregator {

    fun aggregate(primitives: ImportFieldPrimitives): CollageAnalysis {
        val grid = CollageAnalysis.GRID_SIZE
        val cells = grid * grid
        require(primitives.sourceWidth > 0 && primitives.sourceHeight > 0) {
            "source dimensions must be positive"
        }
        for ((name, values) in mapOf(
            "saliency" to primitives.saliency,
            "edgeEnergy" to primitives.edgeEnergy,
            "contrast" to primitives.contrast,
            "structureField" to primitives.structureField,
        )) {
            require(values.size == cells) { "$name must be $cells values, got ${values.size}" }
            require(values.all { it.isFinite() }) { "$name must be finite" }
        }
        require(primitives.tipBoxes.all {
            it.x.isFinite() && it.y.isFinite() && it.width.isFinite() && it.height.isFinite()
        }) { "tip boxes must be finite" }
        require(primitives.faces.all {
            it.box.x.isFinite() && it.box.y.isFinite() &&
                it.box.width.isFinite() && it.box.height.isFinite() && it.confidence.isFinite()
        }) { "face boxes and confidences must be finite" }
        require(primitives.objects.all {
            it.box.x.isFinite() && it.box.y.isFinite() &&
                it.box.width.isFinite() && it.box.height.isFinite() && it.confidence.isFinite()
        }) { "object boxes and confidences must be finite" }

        val center = centerField(grid)
        val base = DoubleArray(cells) { c ->
            0.015 +
                0.34 * primitives.saliency[c] +
                0.25 * primitives.edgeEnergy[c] +
                0.12 * primitives.contrast[c] +
                0.12 * primitives.structureField[c] +
                0.025 * center[c]
        }
        val objectField = DoubleArray(cells)
        val faceField = DoubleArray(cells)

        val regions = ArrayList<AnalysisProtectedRegion>(
            primitives.tipBoxes.size + primitives.objects.size + primitives.faces.size * 2,
        )
        for (tip in primitives.tipBoxes) {
            regions += AnalysisProtectedRegion(
                kind = "structure-tip",
                box = tip,
                confidence = 0.4,
                critical = false,
            )
        }
        for (obj in primitives.objects) {
            val g = gaussianField(obj.box, expand = 0.06, grid = grid)
            for (c in 0 until cells) objectField[c] = max(objectField[c], g[c] * obj.confidence)
            regions += AnalysisProtectedRegion(
                kind = "object",
                box = obj.box,
                confidence = obj.confidence,
                critical = false,
            )
        }
        for (face in primitives.faces) {
            val g = gaussianField(face.box, expand = 0.25, grid = grid)
            for (c in 0 until cells) faceField[c] = max(faceField[c], g[c] * face.confidence)
            regions += AnalysisProtectedRegion(
                kind = "face",
                box = face.box,
                confidence = face.confidence,
                critical = false,
            )
            val b0 = max(0.0, face.box.x - face.box.width * 0.7)
            val b1 = max(0.0, face.box.y - face.box.height * 0.5)
            val context = NormBox(
                b0,
                b1,
                min(1.0, face.box.x + face.box.width * 1.7) - b0,
                min(1.0, face.box.y + face.box.height * 3.2) - b1,
            )
            val gb = gaussianField(context, expand = 0.05, grid = grid)
            for (c in 0 until cells) base[c] += gb[c] * 0.08
            regions += AnalysisProtectedRegion(
                kind = "face-context",
                box = context,
                confidence = 0.3,
                critical = false,
            )
        }

        val tipIndices = regions.indices.filter { i ->
            val box = regions[i].box
            regions[i].kind == "structure-tip" && box.x > 0.08 && box.x < 0.90
        }
        val ordered = tipIndices
            .withIndex()
            .sortedWith(compareBy<IndexedValue<Int>> { regions[it.value].box.y }.thenBy { it.index })
            .map { it.value }
        val personFocus = primitives.faces.size > 5 ||
            primitives.faces.any { it.box.width * it.box.height > 0.005 }
        for (i in ordered.take(2)) {
            regions[i] = regions[i].copy(critical = !personFocus)
        }

        val importance = DoubleArray(cells) { c -> base[c] + 0.65 * objectField[c] + 1.2 * faceField[c] }
        val baselineField = DoubleArray(cells) { c -> base[c] + 1.2 * faceField[c] }

        return CollageAnalysis(
            width = primitives.sourceWidth,
            height = primitives.sourceHeight,
            faces = primitives.faces.toList(),
            objects = primitives.objects.toList(),
            protectedRegions = regions.toList(),
            importance = DoubleArray(cells) { c -> round4(importance[c]) },
            baselineField = DoubleArray(cells) { c -> round4(baselineField[c]) },
            objectField = DoubleArray(cells) { c -> round4(objectField[c]) },
            saliency = DoubleArray(cells) { c -> round4(primitives.saliency[c]) },
            edgeEnergy = DoubleArray(cells) { c -> round4(primitives.edgeEnergy[c]) },
            structureField = DoubleArray(cells) { c -> round4(primitives.structureField[c]) },
        )
    }

    private fun gaussianField(box: NormBox, expand: Double, grid: Int): DoubleArray {
        val out = DoubleArray(grid * grid)
        val sigmaX = max(box.width * (0.5 + expand), 0.025)
        val sigmaY = max(box.height * (0.5 + expand), 0.025)
        val cx = box.x + box.width / 2.0
        val cy = box.y + box.height / 2.0
        for (j in 0 until grid) {
            val yy = j.toDouble() / grid + 0.5 / grid
            for (i in 0 until grid) {
                val xx = i.toDouble() / grid + 0.5 / grid
                val ex = (xx - cx) / sigmaX
                val ey = (yy - cy) / sigmaY
                out[j * grid + i] = exp(-0.5 * ex * ex - 0.5 * ey * ey)
            }
        }
        return out
    }

    private fun centerField(grid: Int): DoubleArray {
        val out = DoubleArray(grid * grid)
        for (j in 0 until grid) {
            val yy = j.toDouble() / grid + 0.5 / grid
            for (i in 0 until grid) {
                val xx = i.toDouble() / grid + 0.5 / grid
                out[j * grid + i] = exp(-((xx - 0.5) * (xx - 0.5) + (yy - 0.5) * (yy - 0.5)) / 0.18)
            }
        }
        return out
    }

    /** Exactly `np.round(v, 4)`: scale, half-even `rint`, descale. */
    internal fun round4(value: Double): Double = Math.rint(value * 10_000.0) / 10_000.0
}
