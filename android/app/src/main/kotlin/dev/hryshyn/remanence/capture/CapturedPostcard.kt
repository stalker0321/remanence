package dev.hryshyn.remanence.capture

import android.graphics.Bitmap
import dev.hryshyn.remanence.core.recognition.PointD
import dev.hryshyn.remanence.core.recognition.QuadCandidate
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The recipient's own photo of the postcard plus where the card sits in it,
 * kept only to stage the capsule opening (the card lifts off the table and
 * turns over). [quad] is in [photo] pixels, ordered top-left, top-right,
 * bottom-right, bottom-left. Never part of the fingerprint or any payload.
 */
class CapturedPostcard(val photo: Bitmap, val quad: List<PointD>) {
    init {
        require(quad.size == 4)
    }

    /** Long/short edge ratio of the card as photographed. */
    val aspect: Double get() = OpeningQuad.aspect(quad)

    /** True when the card is wider than tall in the photo. */
    val landscape: Boolean get() {
        val horizontal = (OpeningQuad.dist(quad[0], quad[1]) + OpeningQuad.dist(quad[3], quad[2])) / 2.0
        val vertical = (OpeningQuad.dist(quad[0], quad[3]) + OpeningQuad.dist(quad[1], quad[2])) / 2.0
        return horizontal >= vertical
    }

    /** Erases the pixels; the reference is dropped by the owner. */
    fun clear() = clearCaptureDisplayStill(photo)
}

/**
 * Picks the card outline for the opening animation. The fingerprint keeps its
 * strict crop rules; for display a smaller card is fine as long as the
 * outline is clearly a postcard: real postcard proportions and strong edge
 * support on every side. Measured on the 158-frame dataset this recovers 31
 * of the 51 guide-fallback frames with no wrong outline.
 */
object OpeningQuad {
    const val MIN_AREA_RATIO = 0.08
    const val MIN_RECTANGULARITY = 0.90
    const val MIN_EDGE_SUPPORT = 0.93
    const val MIN_ASPECT = 1.30
    const val MAX_ASPECT = 1.62

    fun select(
        chosen: QuadCandidate,
        usedGuideFallback: Boolean,
        lineCandidates: List<QuadCandidate>?,
        frameWidth: Int,
        frameHeight: Int,
    ): List<PointD>? {
        val outline = if (!usedGuideFallback) {
            chosen.corners
        } else {
            lineCandidates.orEmpty().firstOrNull(::looksLikePostcard)?.corners ?: return null
        }
        if (outline.size != 4 || outline.any { it.x !in 0.0..frameWidth.toDouble() || it.y !in 0.0..frameHeight.toDouble() }) {
            return null
        }
        return order(outline)
    }

    fun looksLikePostcard(candidate: QuadCandidate): Boolean {
        val edge = candidate.edgeSupport ?: return false
        return candidate.corners.size == 4 &&
            candidate.areaRatio >= MIN_AREA_RATIO &&
            candidate.rectangularity >= MIN_RECTANGULARITY &&
            edge >= MIN_EDGE_SUPPORT &&
            aspect(candidate.corners) in MIN_ASPECT..MAX_ASPECT
    }

    fun aspect(quad: List<PointD>): Double {
        val a = (dist(quad[0], quad[1]) + dist(quad[2], quad[3])) / 2.0
        val b = (dist(quad[1], quad[2]) + dist(quad[3], quad[0])) / 2.0
        return max(a, b) / min(a, b)
    }

    /** Top-left, top-right, bottom-right, bottom-left by coordinate sums. */
    fun order(quad: List<PointD>): List<PointD> {
        val topLeft = quad.minBy { it.x + it.y }
        val bottomRight = quad.maxBy { it.x + it.y }
        val rest = quad.filter { it !== topLeft && it !== bottomRight }
        if (rest.size != 2) return quad
        val topRight = rest.maxBy { it.x - it.y }
        val bottomLeft = rest.minBy { it.x - it.y }
        return listOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    internal fun dist(a: PointD, b: PointD): Double = hypot(a.x - b.x, a.y - b.y)
}
