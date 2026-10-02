package dev.hryshyn.remanence.ui.collage

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Production [CollageCropMatrixSource]: the Android counterpart of the frozen
 * `V4_CROP.crop` + `assignPhotos` cost block (`grammars.js:132-149`), computed
 * from the authoritative cached [CollageAnalysis] (no JS oracle, no repeated
 * detector execution). Feasibility is the frozen baseline crop plus the
 * face/role gates; cost is
 * `|log(sourceAspect/slotAspect)|*0.14 + (1-importance)*0.4 + roleCost +
 * facePixelPenalty`, exactly the frozen weighted fit.
 */
class AndroidCollageCropMatrixSource : CollageCropMatrixSource {

    override suspend fun matrix(
        photos: List<CropMatrixPhoto>,
        requests: List<CropMatrixRequest>,
    ): Map<CropMatrixRequest, CropMatrixCell>? {
        val byId = photos.associateBy { it.contentId }
        // `assignPhotos` object-anchor gate is dataset-wide (`d.photos.some(isObject)`).
        val anyObject = photos.any { isObject(it.analysis) }
        val out = LinkedHashMap<CropMatrixRequest, CropMatrixCell>(requests.size)
        for (request in requests) {
            val photo = byId[request.contentId] ?: return null
            val analysis = photo.analysis ?: return null
            out[request] = evaluate(analysis, anyObject, request)
        }
        return out
    }

    private fun evaluate(analysis: CollageAnalysis, anyObject: Boolean, request: CropMatrixRequest): CropMatrixCell {
        val slot = Envelope50CropSolver.Slot(request.slotWidth, request.slotHeight, request.shape, request.zooms)
        val metrics = Envelope50CropSolver.baseMetrics(analysis, slot)
            ?: return CropMatrixCell(feasible = false, reason = reasonFor(analysis, slot))

        val faces = analysis.faces.filter { it.box.width * it.box.height > 0.003 }
        val hasFace = faces.isNotEmpty()
        when (request.assignmentRole) {
            "human-anchor" -> if (!hasFace) return CropMatrixCell(false)
            "object-anchor" -> if (anyObject && !isObject(analysis)) return CropMatrixCell(false)
        }

        val facePixels = faces.map {
            min(
                it.box.width / metrics.rect.width * request.slotWidth,
                it.box.height / metrics.rect.height * request.slotHeight,
            )
        }
        if (facePixels.any { it < FACE_PIXEL_FLOOR }) return CropMatrixCell(false)

        var roleCost = 0.0
        if (request.assignmentRole == "object-anchor" && hasImportantObject(analysis)) roleCost = -0.15
        if (request.assignmentRole == "vertical-anchor" && analysis.width.toDouble() / analysis.height < 1.0) roleCost = -0.1

        val sourceAspect = analysis.width.toDouble() / analysis.height
        val slotAspect = request.slotWidth / request.slotHeight
        val fit = abs(Math.log(sourceAspect / slotAspect)) * 0.14
        val facePenalty = if (hasFace) max(0.0, 30.0 - facePixels.min()) * 0.01 else 0.0
        val cost = fit + (1.0 - metrics.importance) * 0.4 + roleCost + facePenalty
        return CropMatrixCell(feasible = true, reason = null, cost = cost)
    }

    private fun reasonFor(analysis: CollageAnalysis, slot: Envelope50CropSolver.Slot): CropRejectReason =
        (Envelope50CropSolver.solveDetailed(analysis, slot) as? CropSolveResult.Rejected)?.reason
            ?: CropRejectReason.NO_CROP_CANDIDATE

    private fun isObject(analysis: CollageAnalysis?): Boolean {
        val a = analysis ?: return false
        if (a.faces.any { it.box.width * it.box.height > 0.003 }) return false
        return hasImportantObject(a)
    }

    private fun hasImportantObject(a: CollageAnalysis): Boolean = a.objects.any {
        it.classId !in CONTEXT_CLASS_IDS && it.confidence > 0.48 && it.box.width * it.box.height > 0.06
    }

    private companion object {
        val CONTEXT_CLASS_IDS = setOf(0, 56, 57, 60)
        const val FACE_PIXEL_FLOOR = 11.0
    }
}
