package dev.hryshyn.remanence.ui.create

import dev.hryshyn.remanence.ui.collage.CropTransform
import dev.hryshyn.remanence.ui.collage.FixedFrameCropCorrection
import dev.hryshyn.remanence.ui.collage.NormalizedCrop
import kotlin.math.max

/** Source-coordinate geometry for dragging a frame over the entire upright original. */
internal object CropEditorGeometry {
    private const val MAX_ZOOM = 1.25

    fun clamp(base: NormalizedCrop, transform: CropTransform): CropTransform? {
        if (!valid(base) || !transform.z.isFinite() || !transform.xPercent.isFinite() ||
            !transform.yPercent.isFinite()
        ) return null
        // Zooming out can recover the source around the solver's initial crop.
        // The frame always keeps its slot aspect and never crosses the source edge.
        val zoom = transform.z.coerceIn(max(base.width, base.height), MAX_ZOOM)
        val width = base.width / zoom
        val height = base.height / zoom
        val centerX = (base.x + base.width / 2 - transform.xPercent * width / 100)
            .coerceIn(width / 2, 1 - width / 2)
        val centerY = (base.y + base.height / 2 - transform.yPercent * height / 100)
            .coerceIn(height / 2, 1 - height / 2)
        return CropTransform(
            zoom,
            (base.x + base.width / 2 - centerX) * 100 / width,
            (base.y + base.height / 2 - centerY) * 100 / height,
        )
    }

    fun window(base: NormalizedCrop, transform: CropTransform): NormalizedCrop? =
        clamp(base, transform)?.let { FixedFrameCropCorrection.sourceWindow(base, it) }

    /**
     * Drag deltas and pinch centroid are fractions of the fitted source, excluding
     * letterboxing. Positive drag moves the FRAME in the same direction as the finger.
     * Pinching out narrows the retained area around the pinch centroid.
     */
    fun gesture(
        base: NormalizedCrop,
        transform: CropTransform,
        dragX: Double,
        dragY: Double,
        zoomFactor: Double,
        centroidX: Double,
        centroidY: Double,
    ): CropTransform? {
        if (!dragX.isFinite() || !dragY.isFinite() || !zoomFactor.isFinite() ||
            zoomFactor <= 0 || !centroidX.isFinite() || !centroidY.isFinite()
        ) return null
        val bounded = clamp(base, transform) ?: return null
        val before = window(base, bounded) ?: return null
        val zoom = (bounded.z * zoomFactor).coerceIn(max(base.width, base.height), MAX_ZOOM)
        val scale = bounded.z / zoom
        val width = base.width / zoom
        val height = base.height / zoom
        val left = centroidX + (before.x - centroidX) * scale + dragX
        val top = centroidY + (before.y - centroidY) * scale + dragY
        return clamp(base, CropTransform(
            zoom,
            (base.x + base.width / 2 - left - width / 2) * 100 / width,
            (base.y + base.height / 2 - top - height / 2) * 100 / height,
        ))
    }

    private fun valid(crop: NormalizedCrop): Boolean =
        crop.x.isFinite() && crop.y.isFinite() && crop.width.isFinite() && crop.height.isFinite() &&
            crop.width > 0 && crop.height > 0 && crop.x >= 0 && crop.y >= 0 &&
            crop.x + crop.width <= 1.000001 && crop.y + crop.height <= 1.000001
}
