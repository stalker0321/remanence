package dev.hryshyn.remanence.ui.collage

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * T151 stage 3 — frozen V5 **resolve order**, **complete-safe `assignPhotos`
 * matching** and **DECISIONS candidate cap**.
 *
 * The frozen `resolve()` (`grammars.js:169-179`) walks `plans()` in enumeration
 * order, runs `assignPhotos()` then `validate()` on each, and returns the
 * **first** branch that passes; a rejected branch never aborts the grammar.
 * `assignPhotos()` (`grammars.js:129-155`) uses:
 *  - a readability pre-check that returns the plan unchanged (no assignment),
 *  - a per-(photo,slot) feasibility+cost matrix, and
 *  - an exhaustive ≤`5!` walk minimising the summed cost, with
 *    `lockOrder:true` restricting to the source-order diagonal and the default
 *    complete-safe matching over every permutation.
 *
 * The per-(photo,slot) matrix (feasibility + cost) is supplied by an
 * [AssignmentTable] recorded from the real frozen `V4_CROP.crop` over the T153
 * corpus features; this object owns the walk, the validate port and the resolve
 * order. It edits no schema/renderer/UI and needs no duplicate sealed schema.
 *
 * `FrozenCandidatePolicy` applies the DECISIONS presentation cap (grammar-first,
 * at most two geometrically distinct layouts per grammar, up to twelve total)
 * exactly as the playground `buildExpressions` round-robin does.
 */
internal object FrozenResolve {

    const val CANVAS_W = 360.0
    const val CANVAS_H = 640.0

    /** Frozen `assignPhotos` failure literal (`grammars.js:152`). */
    const val ASSIGNMENT_FAILURE =
        "No complete safe source-to-slot matching at readable photo sizes"

    data class Photo(val id: String, val width: Double, val height: Double)

    /** Frozen `assignPhotos` result. [earlyReadability] = readability pre-check return. */
    data class Assignment(
        val failure: String?,
        val mode: String?,
        val compositionOrder: List<String>,
        val feasibleAssignments: Int,
        val cost: Double?,
        val earlyReadability: Boolean,
    ) {
        val ok: Boolean get() = failure == null && !earlyReadability
    }

    data class Resolution(
        val status: String,
        val branch: String?,
        val assignmentMode: String?,
        val compositionOrder: List<String>,
    )

    /** One branch's expected/actual resolve contribution, for the differential. */
    data class BranchResult(
        val index: Int,
        val assignment: Assignment,
        val validateOk: Boolean,
    )

    /**
     * Frozen `assignPhotos(d, g, plan, options)` structural outcome.
     * [matrix] is `matrix[slot][photo]` = frozen cell cost, or null if the crop
     * was rejected / the role was infeasible. [lockOrder] selects the
     * source-order diagonal; otherwise complete-safe matching.
     */
    fun assign(
        plan: FrozenGrammarPlans.Plan,
        photos: List<Photo>,
        matrix: List<List<Double?>>,
        lockOrder: Boolean,
    ): Assignment {
        // `grammars.js:131` readability pre-check returns the plan unchanged.
        if (plan.tiles.any { it.rect.w < 84.0 || it.rect.h < 84.0 || it.rect.w * it.rect.h < 9500.0 }) {
            return Assignment(null, null, emptyList(), 0, null, earlyReadability = true)
        }
        val n = photos.size
        var bestPath: List<Int>? = null
        var bestCost = 0.0
        var visits = 0
        val path = ArrayList<Int>(n)
        fun step(i: Int, used: Int, cost: Double) {
            if (i == n) {
                visits++
                if (bestPath == null || cost < bestCost - 1e-9) {
                    bestPath = ArrayList(path)
                    bestCost = cost
                }
                return
            }
            for (j in 0 until n) {
                if (used and (1 shl j) != 0) continue
                if (lockOrder && i != j) continue
                val cell = matrix.getOrNull(i)?.getOrNull(j) ?: continue
                path.add(j)
                step(i + 1, used or (1 shl j), cost + cell)
                path.removeAt(path.size - 1)
            }
        }
        step(0, 0, 0.0)
        val solved = bestPath
        if (solved == null) {
            return Assignment(ASSIGNMENT_FAILURE, null, emptyList(), visits, null, earlyReadability = false)
        }
        return Assignment(
            failure = null,
            mode = if (lockOrder) "source-order-control" else "complete-safe-matching",
            compositionOrder = solved.map { photos[it].id },
            feasibleAssignments = visits,
            cost = round4(bestCost),
            earlyReadability = false,
        )
    }

    /**
     * Frozen `validate(d, g, plan)` structural verdict (`grammars.js:158-167`).
     * Crop safety is already encoded in [Assignment] feasibility (every assigned
     * tile came from a feasible cell), so the crop term is satisfied for a
     * solved assignment; the readability pre-check branch is rejected here too.
     */
    fun validate(plan: FrozenGrammarPlans.Plan): Boolean {
        val regions = ArrayList<FrozenGrammarPlans.Rect>(plan.tiles.size + 4)
        for (tile in plan.tiles) regions.add(tile.rect)
        plan.note?.let { regions.add(it.rect) }
        plan.music?.let { regions.add(it.rect) }
        for (mark in plan.provenance) regions.add(mark.rect)

        for (r in regions) {
            if (r.x < -0.01 || r.y < -0.01 || r.x + r.w > CANVAS_W + 0.01 || r.y + r.h > CANVAS_H + 0.01 ||
                r.w <= 0.0 || r.h <= 0.0
            ) {
                return false
            }
        }
        for (i in regions.indices) {
            for (j in i + 1 until regions.size) {
                val a = regions[i]
                val b = regions[j]
                if (min(a.x + a.w, b.x + b.w) - max(a.x, b.x) > 0.8 &&
                    min(a.y + a.h, b.y + b.h) - max(a.y, b.y) > 0.8
                ) {
                    return false
                }
            }
        }

        val music = plan.music
        if (music != null && music.mode.startsWith("tile") && music.rect.h < 142.0) return false

        for (tile in plan.tiles) {
            if (min(tile.rect.w, tile.rect.h) < 84.0 || tile.rect.w * tile.rect.h < 9500.0) return false
        }
        for (mark in plan.provenance) {
            val horizontal = mark.orientation == "horizontal"
            val width = if (horizontal) mark.rect.w else mark.rect.h
            if (width <= 0.0) return false
            val line = ceil(mark.size * 1.02)
            // T153/T151 line-height stub: measured height == ceil(line) == line.
            val measuredHeight = ceil(line)
            val box = if (horizontal) mark.rect.h else mark.rect.w
            if (measuredHeight > box + 1.0) return false
        }

        val ellipseFactor = Math.PI / 4.0
        val area = plan.tiles.sumOf { tile ->
            tile.rect.w * tile.rect.h * (if (tile.shape == "ellipse") ellipseFactor else 1.0)
        } / (CANVAS_W * CANVAS_H)
        return area >= 0.42
    }

    /**
     * Frozen `resolve(d, g, {lockOrder})` over pre-enumerated [branches] with
     * their frozen [matrices] (index-aligned). First assignment-successful,
     * validate-passing branch wins; otherwise `Incompatible`.
     */
    fun resolve(
        branches: List<FrozenGrammarPlans.Plan>,
        photos: List<Photo>,
        matrices: List<List<List<Double?>>>,
        lockOrder: Boolean,
    ): Resolution {
        for (i in branches.indices) {
            val assignment = assign(branches[i], photos, matrices[i], lockOrder)
            if (assignment.failure != null) continue
            if (!validate(branches[i])) continue
            return Resolution("Valid", branches[i].branch, assignment.mode, assignment.compositionOrder)
        }
        return Resolution("Incompatible", null, null, emptyList())
    }

    private fun round4(value: Double): Double = Math.round(value * 10_000.0) / 10_000.0
}

/**
 * T151 stage 3 — DECISIONS presentation cap.
 *
 * Frozen `DECISIONS.md`: "Candidate selection is grammar-first, with at most two
 * geometrically distinct layouts per grammar and up to twelve expressions
 * total." The playground (`app/playground.js` `buildExpressions`) realises this
 * as a grammar-breadth round-robin: one layout from each grammar, then the
 * second layout, until [MAX_TOTAL] is reached
 * (`orderPolicy:'grammar-breadth-then-second-layout-seeded'`,
 * `maxLayoutsPerGrammar:2`, `TARGET_EXPRESSIONS=12`). This object uses the same
 * structure with a deterministic identity grammar order (the playground also
 * shuffles the grammar keys and bucket layouts by the session seed, which does
 * not change the breadth-first/cap invariants).
 */
internal object FrozenCandidatePolicy {

    const val MAX_PER_GRAMMAR = 2
    const val MAX_TOTAL = 12

    data class Selected(val grammarId: Int, val branch: String)

    /**
     * Grammar-breadth round-robin over [buckets] (`grammarId` -> candidate
     * branches in the adapter's `resolveCandidates` order). Each grammar
     * contributes at most [maxPerGrammar]; the total is capped at [maxTotal].
     */
    fun rank(
        buckets: List<Pair<Int, List<String>>>,
        maxPerGrammar: Int = MAX_PER_GRAMMAR,
        maxTotal: Int = MAX_TOTAL,
    ): List<Selected> {
        val queues = buckets.map { (gid, branches) ->
            gid to ArrayDeque(branches.take(maxPerGrammar))
        }
        val out = ArrayList<Selected>(maxTotal)
        while (out.size < maxTotal && queues.any { it.second.isNotEmpty() }) {
            for ((gid, queue) in queues) {
                if (queue.isNotEmpty()) out.add(Selected(gid, queue.removeFirst()))
                if (out.size >= maxTotal) break
            }
        }
        return out
    }
}
