package dev.hryshyn.remanence.ui.collage

/**
 * Real Android feasibility + cost seam for the frozen complete-safe
 * `assignPhotos` matching (`grammars.js:129-155`).
 *
 * A [CropMatrixRequest] is one (photo, slot) attempted pairing; the slot carries
 * the frozen tile geometry, shape, zooms and `assignmentRole`. A
 * [CropMatrixCell] is the frozen outcome: feasible (`assignPhotos` matrix cell
 * non-null) plus the frozen cost
 * `fit + (1-importance)*.4 + roleCost + facePixelPenalty`. The full N×N matrix
 * per branch is what [FrozenResolve] walks.
 *
 * The matrix must be computed from the authoritative cached [CollageAnalysis]
 * (no JS oracle, no repeated detector execution); [AndroidCollageCropMatrixSource]
 * is the production implementation. A `null` result means the authoritative
 * analysis is unavailable and the producer must fail closed.
 */
data class CropMatrixRequest(
    val contentId: String,
    val slotWidth: Double,
    val slotHeight: Double,
    val shape: String = "rect",
    val zooms: List<Double> = listOf(1.0),
    val assignmentRole: String? = null,
)

data class CropMatrixCell(
    val feasible: Boolean,
    val reason: CropRejectReason? = null,
    val cost: Double? = null,
)

/** One photo's matrix context (identity + authoritative analysis). */
data class CropMatrixPhoto(
    val contentId: String,
    val widthPx: Int,
    val heightPx: Int,
    val analysis: CollageAnalysis?,
)

fun interface CollageCropMatrixSource {
    /**
     * Evaluate [requests] against the cached analyses of [photos]. Returns `null`
     * when any required analysis is unavailable (authoritative fail-closed).
     */
    suspend fun matrix(
        photos: List<CropMatrixPhoto>,
        requests: List<CropMatrixRequest>,
    ): Map<CropMatrixRequest, CropMatrixCell>?
}
