package postmark.core.recognition

/** A frame-independent rectangle used by both the camera overlay and crop. */
data class NormalizedGuideRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    init {
        require(left in 0.0..1.0 && top in 0.0..1.0)
        require(right in 0.0..1.0 && bottom in 0.0..1.0)
        require(left < right && top < bottom)
    }

    val width: Double get() = right - left
    val height: Double get() = bottom - top

    fun toPixels(frameWidth: Int, frameHeight: Int): List<PointD> =
        toPixels(frameWidth.toDouble(), frameHeight.toDouble())

    fun toPixels(frameWidth: Double, frameHeight: Double): List<PointD> = listOf(
        PointD(left * frameWidth, top * frameHeight),
        PointD(right * frameWidth, top * frameHeight),
        PointD(right * frameWidth, bottom * frameHeight),
        PointD(left * frameWidth, bottom * frameHeight),
    )

    fun toOverlay(frameWidth: Int, frameHeight: Int): GuideOverlay {
        val corners = toPixels(frameWidth, frameHeight)
        return GuideOverlay(
            left = corners[0].x,
            top = corners[0].y,
            right = corners[2].x,
            bottom = corners[2].y,
        )
    }
}

/**
 * The single postcard guide definition shared by Compose and still crops.
 * It deliberately describes a landscape card even when the camera preview is
 * portrait: the available frame is padded, then the guide is centered and
 * fit to the same 3:2 card shape at every resolution/aspect ratio.
 */
object PostcardGuideGeometry {

    const val LANDSCAPE_ASPECT_RATIO: Double = 1.5
    const val MAX_WIDTH_FRACTION: Double = 0.86
    const val MAX_HEIGHT_FRACTION: Double = 0.78

    fun normalizedFor(frameWidth: Double, frameHeight: Double): NormalizedGuideRect {
        require(frameWidth > 0.0 && frameHeight > 0.0)
        val maxWidth = frameWidth * MAX_WIDTH_FRACTION
        val maxHeight = frameHeight * MAX_HEIGHT_FRACTION
        var width = maxWidth
        var height = width / LANDSCAPE_ASPECT_RATIO
        if (height > maxHeight) {
            height = maxHeight
            width = height * LANDSCAPE_ASPECT_RATIO
        }
        return NormalizedGuideRect(
            left = (frameWidth - width) / 2.0 / frameWidth,
            top = (frameHeight - height) / 2.0 / frameHeight,
            right = (frameWidth + width) / 2.0 / frameWidth,
            bottom = (frameHeight + height) / 2.0 / frameHeight,
        )
    }

    fun normalizedFor(frameWidth: Int, frameHeight: Int): NormalizedGuideRect =
        normalizedFor(frameWidth.toDouble(), frameHeight.toDouble())

    fun overlayFor(frameWidth: Int, frameHeight: Int): GuideOverlay =
        normalizedFor(frameWidth, frameHeight).toOverlay(frameWidth, frameHeight)
}

/** Selected crop with provenance kept explicit for diagnostics and tests. */
data class PostcardCropSelection(
    val candidate: QuadCandidate,
    val usedGuideFallback: Boolean,
)

/** Chooses the best credible contour, or the bounded guide crop if none pass. */
class PostcardCropSelector(private val profile: RecognitionProfile) {

    fun select(
        candidates: List<QuadCandidate>,
        frameWidth: Int,
        frameHeight: Int,
    ): PostcardCropSelection {
        require(frameWidth > 0 && frameHeight > 0)
        val guide = PostcardGuideGeometry.overlayFor(frameWidth, frameHeight)
        val credible = candidates.filter { isCredible(it, frameWidth, frameHeight) }
        val selected = CandidateRanker(profile)
            .rank(
                inputs = credible.map(::CandidateWithEdges),
                frameDiagonalPx = kotlin.math.hypot(frameWidth.toDouble(), frameHeight.toDouble()),
                guide = guide,
            )
            .firstOrNull { it.confidence >= profile.ranking.minContourConfidence }
            ?.candidate
        if (selected != null) return PostcardCropSelection(selected, usedGuideFallback = false)

        val corners = PostcardGuideGeometry.normalizedFor(frameWidth, frameHeight)
            .toPixels(frameWidth, frameHeight)
        val areaRatio = kotlin.math.abs(CornerGeometry.signedArea(corners)) /
            (frameWidth.toDouble() * frameHeight.toDouble())
        return PostcardCropSelection(
            candidate = QuadCandidate(corners, areaRatio, rectangularity = 1.0),
            usedGuideFallback = true,
        )
    }

    private fun isCredible(candidate: QuadCandidate, frameWidth: Int, frameHeight: Int): Boolean {
        if (!candidate.areaRatio.isFinite() || !candidate.rectangularity.isFinite()) return false
        if (candidate.areaRatio < profile.capture.minCardAreaRatio) return false
        if (candidate.rectangularity < profile.capture.minRectangularity) return false
        if (candidate.corners.size != 4) return false

        val frameWidthPx = frameWidth.toDouble()
        val frameHeightPx = frameHeight.toDouble()
        if (candidate.corners.any { corner ->
                corner.x !in 0.0..frameWidthPx || corner.y !in 0.0..frameHeightPx
            }
        ) {
            return false
        }
        if (CornerGeometry.validateQuad(candidate.corners) !is CornerGeometry.QuadValidation.Valid) {
            return false
        }

        val actualAreaRatio = kotlin.math.abs(CornerGeometry.signedArea(candidate.corners)) /
            (frameWidthPx * frameHeightPx)
        if (actualAreaRatio < profile.capture.minCardAreaRatio) return false

        val edges = candidate.corners.mapIndexed { index, corner ->
            val next = candidate.corners[(index + 1) % candidate.corners.size]
            kotlin.math.hypot(next.x - corner.x, next.y - corner.y)
        }
        val horizontal = (edges[0] + edges[2]) / 2.0
        val vertical = (edges[1] + edges[3]) / 2.0
        val longEdge = maxOf(horizontal, vertical)
        val shortEdge = minOf(horizontal, vertical)
        if (!longEdge.isFinite() || !shortEdge.isFinite() || shortEdge <= 0.0) return false
        val aspectRatio = longEdge / shortEdge
        return aspectRatio in profile.capture.aspectRatioMin..profile.capture.aspectRatioMax
    }
}
