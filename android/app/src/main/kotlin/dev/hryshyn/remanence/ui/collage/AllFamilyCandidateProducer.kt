package dev.hryshyn.remanence.ui.collage

import dev.hryshyn.remanence.core.model.GeneratorExpression
import kotlin.math.floor

/** One verified photo input: sealed content identity + authoritative analysis. */
internal data class AllFamilyPhoto(
    val contentId: String,
    val widthPx: Int,
    val heightPx: Int,
    val hasProminentFace: Boolean,
    val analysis: CollageAnalysis?,
)

/** Verified photo identity/dimensions; analysis is supplied by the shared cache. */
internal data class PhotoDescriptor(
    val contentId: String,
    val widthPx: Int,
    val heightPx: Int,
    val hasProminentFace: Boolean,
)

/** Verified sender/note/music inputs plus the analysed photos. */
internal data class AllFamilyInput(
    val id: String,
    val sender: String,
    val note: String?,
    val music: Boolean,
    val artwork: Boolean,
    val photos: List<AllFamilyPhoto>,
)

/** One matched slot: sealed content + integer canvas cell + frozen envelope-50 crop. */
internal data class MountedPlacement(
    val slotIndex: Int,
    val contentId: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val shape: String,
    val crop: GeneratorExpression.CropWindow,
)

/** One mounted all-family candidate: frozen plan + complete-safe assignment. */
internal data class AllFamilyCandidate(
    val grammarId: Int,
    val branchId: String,
    val assignmentMode: String,
    val compositionOrder: List<String>,
    val plan: FrozenGrammarPlans.Plan,
    val verdict: FrozenPlanAdapter.Verdict,
    val placements: List<MountedPlacement>,
) {
    /** The representable V3/V4 projection (geometry + typed gaps). */
    val projection: FrozenPlanAdapter.Projection
        get() = when (verdict) {
            is FrozenPlanAdapter.Verdict.Representable -> verdict.projection
            is FrozenPlanAdapter.Verdict.Blocked -> verdict.projection
        }
}

internal sealed interface AllFamilyOutcome {
    data class Candidates(
        val candidates: List<AllFamilyCandidate>,
        /** Every grammar that produced at least one valid, distinct layout. */
        val perGrammar: Map<Int, List<String>>,
        /** Every applicable grammar that was actually enumerated (all 14 when applicable). */
        val attempted: Set<Int>,
    ) : AllFamilyOutcome

    data class Unavailable(val reason: String) : AllFamilyOutcome
}

/**
 * T151 production mount — enumerates **all 14 frozen families**, matches photos
 * to slots with the frozen complete-safe assignment ([FrozenResolve]), orders
 * candidates by the frozen first-valid resolve order and applies the DECISIONS
 * presentation cap ([FrozenCandidatePolicy]).
 *
 * Crop feasibility and cost come from the real Android matrix source
 * ([CollageCropMatrixSource], backed by the authoritative [CollageAnalysis]
 * cache); the sealed placements use the authoritative crop source
 * ([CollageCropSource]). No JS oracle. Provenance/decor/shape/music payloads are
 * carried on the [plan]/[verdict] for the T154 V4 expression mapping; nothing is
 * silently dropped.
 *
 * Verified inputs: blank sender omits the sender mark; blank/absent note and
 * absent artwork are distinct states and are passed through exactly.
 */
internal class AllFamilyCandidateProducer(
    private val cropMatrix: CollageCropMatrixSource,
    private val cropSource: CollageCropSource,
    private val measure: FrozenGrammarPlans.TextMeasurer = FrozenGrammarPlans.LINE_HEIGHT_STUB,
) {

    suspend fun produce(input: AllFamilyInput): AllFamilyOutcome {
        if (input.photos.size !in MIN_PHOTOS..MAX_PHOTOS) {
            return AllFamilyOutcome.Candidates(emptyList(), emptyMap(), emptySet())
        }
        val sender = input.sender.trim()
        val d = FrozenGrammarPlans.Input(
            id = input.id,
            sender = sender,
            photos = input.photos.map {
                FrozenGrammarPlans.Photo(it.contentId, it.widthPx.toDouble(), it.heightPx.toDouble(), it.hasProminentFace)
            },
            note = input.note?.takeIf { it.isNotBlank() },
            music = if (input.music) FrozenGrammarPlans.MusicInput(input.artwork) else null,
        )
        val matrixPhotos = input.photos.map { CropMatrixPhoto(it.contentId, it.widthPx, it.heightPx, it.analysis) }

        // Phase 1 — enumerate applicable families/branches and collect the union
        // of (photo, slot) matrix requests so the analysis runs once.
        val plansByGrammar = LinkedHashMap<Int, List<FrozenGrammarPlans.Plan>>()
        val requests = LinkedHashSet<CropMatrixRequest>()
        for (gid in FrozenGrammarPlans.ACTIVE_GRAMMAR_IDS) {
            val def = FrozenGrammarPlans.def(gid)
            if (!FrozenGrammarPlans.applicability(d, def).ok) continue
            val plans = FrozenGrammarPlans.plans(d, def, FrozenGrammarPlans.noteState(d, def, measure), measure)
                .map { if (sender.isEmpty()) it.copy(provenance = it.provenance.filter { mark -> mark.role != "sender" }) else it }
            if (plans.isEmpty()) continue
            plansByGrammar[gid] = plans
            for (plan in plans) {
                for (tile in plan.tiles) {
                    for (photo in input.photos) {
                        requests += CropMatrixRequest(
                            contentId = photo.contentId,
                            slotWidth = tile.rect.w,
                            slotHeight = tile.rect.h,
                            shape = tile.shape,
                            zooms = tile.zooms,
                            assignmentRole = tile.assignmentRole,
                        )
                    }
                }
            }
        }
        if (plansByGrammar.isEmpty()) return AllFamilyOutcome.Candidates(emptyList(), emptyMap(), emptySet())

        val cells = cropMatrix.matrix(matrixPhotos, requests.toList())
            ?: return AllFamilyOutcome.Unavailable(NO_ANALYSIS)

        // Phase 2 — complete-safe assignment + validate, first-valid order, up to
        // two geometrically distinct layouts per grammar.
        val photos = input.photos.map { FrozenResolve.Photo(it.contentId, it.widthPx.toDouble(), it.heightPx.toDouble()) }
        val selectedPerGrammar = LinkedHashMap<Int, MutableList<SelectedBranch>>()
        val buckets = ArrayList<Pair<Int, List<String>>>()
        for ((gid, plans) in plansByGrammar) {
            val matrices = plans.map { plan -> plan.tiles.map { tile -> input.photos.map { photo ->
                val cell = cells[CropMatrixRequest(photo.contentId, tile.rect.w, tile.rect.h, tile.shape, tile.zooms, tile.assignmentRole)]
                if (cell?.feasible == true) cell.cost else null
            } } }
            val chosen = ArrayList<SelectedBranch>()
            val seen = HashSet<String>()
            for (index in plans.indices) {
                if (chosen.size >= FrozenCandidatePolicy.MAX_PER_GRAMMAR) break
                val assignment = FrozenResolve.assign(plans[index], photos, matrices[index], lockOrder = false)
                if (!assignment.ok) continue
                if (!FrozenResolve.validate(plans[index])) continue
                val key = geometryKey(plans[index])
                if (!seen.add(key)) continue
                chosen += SelectedBranch(gid, index, plans[index], assignment)
            }
            if (chosen.isNotEmpty()) {
                selectedPerGrammar[gid] = chosen
                buckets += gid to chosen.map { it.plan.branch }
            }
        }
        val ranked = FrozenCandidatePolicy.rank(buckets)

        // Phase 3 — mount the sealed envelope-50 placements for the capped set.
        val byGrammar = selectedPerGrammar
        val candidates = ArrayList<AllFamilyCandidate>(ranked.size)
        for (selection in ranked) {
            val branch = byGrammar[selection.grammarId]?.firstOrNull { it.plan.branch == selection.branch } ?: continue
            val placementRequests = branch.plan.tiles.mapIndexed { slot, tile ->
                CollageCropRequest(
                    contentId = branch.assignment.compositionOrder[slot],
                    slotWidth = floor(tile.rect.w).toInt(),
                    slotHeight = floor(tile.rect.h).toInt(),
                    shape = tile.shape,
                    zooms = tile.zooms,
                )
            }
            // A missing `cropsFor` map is a global authoritative-analysis
            // failure; a missing per-slot key is a rejected branch that must be
            // omitted with its healthy siblings still offered (frozen
            // `resolve()` semantics), never a whole-run abort.
            val crops = cropSource.cropsFor(placementRequests) ?: return AllFamilyOutcome.Unavailable(NO_ANALYSIS)
            val placements = ArrayList<MountedPlacement>(branch.plan.tiles.size)
            var missingCrop = false
            for (slot in branch.plan.tiles.indices) {
                val tile = branch.plan.tiles[slot]
                val window = crops[placementRequests[slot].contentId]
                if (window == null) {
                    missingCrop = true
                    break
                }
                placements += MountedPlacement(
                    slotIndex = slot,
                    contentId = branch.assignment.compositionOrder[slot],
                    x = floor(tile.rect.x).toInt(),
                    y = floor(tile.rect.y).toInt(),
                    width = floor(tile.rect.w).toInt(),
                    height = floor(tile.rect.h).toInt(),
                    shape = tile.shape,
                    crop = window,
                )
            }
            if (missingCrop) continue
            candidates += AllFamilyCandidate(
                grammarId = selection.grammarId,
                branchId = branch.plan.branch,
                assignmentMode = branch.assignment.mode ?: "complete-safe-matching",
                compositionOrder = branch.assignment.compositionOrder,
                plan = branch.plan,
                verdict = FrozenPlanAdapter.adapt(selection.grammarId, branch.plan),
                placements = placements,
            )
        }
        return AllFamilyOutcome.Candidates(
            candidates,
            selectedPerGrammar.mapValues { (_, v) -> v.map { it.plan.branch } },
            plansByGrammar.keys.toSet(),
        )
    }

    private data class SelectedBranch(
        val grammarId: Int,
        val index: Int,
        val plan: FrozenGrammarPlans.Plan,
        val assignment: FrozenResolve.Assignment,
    )

    private fun geometryKey(plan: FrozenGrammarPlans.Plan): String =
        plan.tiles.joinToString(";") { tile ->
            listOf(tile.rect.x, tile.rect.y, tile.rect.w, tile.rect.h, tile.shape).joinToString(",")
        }

    companion object {
        const val MIN_PHOTOS = 3
        const val MAX_PHOTOS = 5
        const val NO_ANALYSIS = "authoritative collage analysis is not available on this build"

        /**
         * Fill [AllFamilyPhoto]s from the shared authoritative analysis cache so
         * each photo's detectors run exactly once per session; `null` fails
         * closed when any analysis is unavailable.
         */
        suspend fun loadPhotos(
            descriptors: List<PhotoDescriptor>,
            analyses: CollageAnalysisSource,
        ): List<AllFamilyPhoto>? {
            val out = ArrayList<AllFamilyPhoto>(descriptors.size)
            for (descriptor in descriptors) {
                val analysis = analyses.analysisFor(descriptor.contentId) ?: return null
                out += AllFamilyPhoto(
                    contentId = descriptor.contentId,
                    widthPx = descriptor.widthPx,
                    heightPx = descriptor.heightPx,
                    hasProminentFace = descriptor.hasProminentFace,
                    analysis = analysis,
                )
            }
            return out
        }
    }
}
