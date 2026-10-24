package dev.hryshyn.remanence.core.recognition

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.DMatch
import org.opencv.core.KeyPoint
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.SIFT
import org.opencv.imgproc.Imgproc

/**
 * Port of the frozen postcard matcher (`postcard_matcher_reference_v1`:
 * `vision.py`, `engine.py`, `frame_first_v2/run.py`, `mask_geometry_v2`
 * adapter, `frame_first_safety_v2` strict_combined).
 *
 * Sender side: decode -> GrabCut auto-crop (or the full image) -> 6x6 grid
 * SIFT. Recipient side: decode -> centred 2:3 frame -> ONNX visible mask ->
 * foreground ROI -> grid SIFT with keypoint centres inside the mask. Matching:
 * soft-assigned TF-IDF over a frozen 1024-word vocabulary, top-8 shortlist,
 * ratio-tested unique matches, MAGSAC homography, projected-bbox checks, the
 * frozen accept floors, then the strict_combined gate.
 *
 * Nearest neighbours are exact (brute force) where the reference used FLANN;
 * decisions are compared against the reference image by image.
 */
object FrozenMatcherS3 {
    const val DECODE_MAX_SIDE = 960
    const val TOP_K = 8
    const val THRESHOLD = 12.0
    const val MARGIN = 1.6
    private const val GRID = 6
    private const val PER_CELL = 45
    private const val RATIO = 0.75

    /** Keypoint positions (x,y pairs), uint8 SIFT descriptors and the image size they came from. */
    class Features(
        val xy: FloatArray,
        val descriptors: ByteArray,
        val width: Int,
        val height: Int,
        /** Where this ROI sits inside the 2:3 frame, and the frame size (recipient side). */
        val originX: Int = 0,
        val originY: Int = 0,
        val frameWidth: Int = width,
        val frameHeight: Int = height,
    ) {
        val count: Int get() = xy.size / 2
    }

    /** Mask statistics the safety gate reads (`mask_geometry.mask_quality`). */
    data class MaskQuality(val components: Int)

    data class Candidate(
        val id: String,
        val retrievalScore: Double,
        val rawScore: Double,
        val finalScore: Double,
        val inliers: Int,
        val matches: Int,
        val inlierRatio: Double,
        val coverage: Double,
        val geometryVerified: Boolean,
        val geometryReason: String,
        val vetoed: Boolean,
        /** The whole reference card, projected into the frame, contains the frame centre. */
        val coversFrameCentre: Boolean = false,
        /** Distance from the frame centre to the projected card, over the frame diagonal; 0 when inside. */
        val centreDistance: Double = Double.POSITIVE_INFINITY,
    )

    data class Decision(
        /** Matcher accept before the safety gate. */
        val baselineAccepted: Boolean,
        /** Final accept after strict_combined. */
        val accepted: Boolean,
        val id: String?,
        val best: Candidate?,
        val runnerUpScore: Double,
        val separation: Double,
        val gateReject: StrictCombinedRejectReason?,
        val candidates: List<Candidate>,
    )

    // ------------------------------------------------------------------ features

    private fun sift(): SIFT = SIFT.create(0, 3, 0.018, 12.0, 1.6)

    /** `vision.features` / `adapter.features_masked`: grid-selected SIFT, optionally centre-filtered. */
    fun gridFeatures(bgr: Mat, mask: Mat? = null): Features {
        val gray = Mat()
        try {
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            val detector = sift()
            val detected = MatOfKeyPoint()
            detector.detect(gray, detected)
            java.lang.ref.Reference.reachabilityFence(detector)
            val all = detected.toArray()
            detected.release()
            // Stable sort by response, strongest first, then at most 45 per 6x6 cell.
            val sorted = all.indices.sortedWith(compareByDescending<Int> { all[it].response })
            val buckets = LinkedHashMap<Int, MutableList<KeyPoint>>()
            val w = gray.cols()
            val h = gray.rows()
            for (i in sorted) {
                val p = all[i]
                val cell = min(GRID - 1, (p.pt.x * GRID / w).toInt()) * GRID + min(GRID - 1, (p.pt.y * GRID / h).toInt())
                val group = buckets.getOrPut(cell) { ArrayList() }
                if (group.size < PER_CELL) group.add(p)
            }
            var selected: List<KeyPoint> = buckets.values.flatten()
            if (mask != null) {
                val mw = mask.cols()
                val mh = mask.rows()
                val bytes = ByteArray(mw * mh)
                mask.get(0, 0, bytes)
                selected = selected.filter { p ->
                    val x = Math.rint(p.pt.x).toInt()
                    val y = Math.rint(p.pt.y).toInt()
                    x in 0 until mw && y in 0 until mh && bytes[y * mw + x].toInt() != 0
                }
            }
            if (selected.isEmpty()) return Features(FloatArray(0), ByteArray(0), w, h)
            val keypoints = MatOfKeyPoint(*selected.toTypedArray())
            val descriptors = Mat()
            detector.compute(gray, keypoints, descriptors)
            // The Java wrapper frees the native detector when collected; keep it alive across the call.
            java.lang.ref.Reference.reachabilityFence(detector)
            val kept = keypoints.toArray()
            keypoints.release()
            if (descriptors.empty()) return Features(FloatArray(0), ByteArray(0), w, h)
            val n = descriptors.rows()
            val raw = FloatArray(n * 128)
            descriptors.get(0, 0, raw)
            descriptors.release()
            val bytes = ByteArray(n * 128) { i -> Math.rint(raw[i].toDouble()).coerceIn(0.0, 255.0).toInt().toByte() }
            val xy = FloatArray(n * 2)
            for (i in 0 until n) {
                xy[2 * i] = kept[i].pt.x.toFloat()
                xy[2 * i + 1] = kept[i].pt.y.toFloat()
            }
            return Features(xy, bytes, w, h)
        } finally {
            gray.release()
        }
    }

    /** RootSIFT: L1-normalise then square-root (`vision.root`). */
    fun root(descriptors: ByteArray, count: Int): Mat {
        val out = FloatArray(count * 128)
        for (i in 0 until count) {
            var sum = 0.0
            for (j in 0 until 128) sum += (descriptors[i * 128 + j].toInt() and 0xFF)
            val norm = max(sum, 1e-12)
            for (j in 0 until 128) out[i * 128 + j] = sqrt((descriptors[i * 128 + j].toInt() and 0xFF) / norm).toFloat()
        }
        return Mat(count, 128, CvType.CV_32F).apply { put(0, 0, out) }
    }

    // ------------------------------------------------------------------ sender crop

    /** `vision.auto_crop`: GrabCut silhouette -> rectified crop, else the full image. */
    fun autoCrop(bgr: Mat): Mat {
        val corners = grabCutQuad(bgr) ?: return bgr.clone()
        val (tl, tr, br, bl) = corners
        val outW = max(dist(tr, tl), dist(br, bl)).toInt()
        val outH = max(dist(bl, tl), dist(br, tr)).toInt()
        if (min(outW, outH) < 40) return bgr.clone()
        val src = MatOfPoint2f(*corners.toTypedArray())
        val dst = MatOfPoint2f(
            Point(0.0, 0.0), Point(outW - 1.0, 0.0), Point(outW - 1.0, outH - 1.0), Point(0.0, outH - 1.0),
        )
        val transform = Imgproc.getPerspectiveTransform(src, dst)
        val crop = Mat()
        Imgproc.warpPerspective(bgr, crop, transform, Size(outW.toDouble(), outH.toDouble()))
        src.release(); dst.release(); transform.release()
        return crop
    }

    private fun grabCutQuad(image: Mat): List<Point>? {
        val width = image.cols()
        val height = image.rows()
        val smallW = min(240, width)
        val smallH = max(32, pyRound(height.toDouble() * smallW / width))
        val small = Mat()
        Imgproc.resize(image, small, Size(smallW.toDouble(), smallH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val mask = Mat.zeros(smallH, smallW, CvType.CV_8UC1)
        val bg = Mat.zeros(1, 65, CvType.CV_64F)
        val fg = Mat.zeros(1, 65, CvType.CV_64F)
        Core.setRNGSeed(42)
        val insetX = max(2, pyRound(smallW * .04))
        val insetY = max(2, pyRound(smallH * .04))
        Imgproc.grabCut(small, mask, Rect(insetX, insetY, smallW - 2 * insetX, smallH - 2 * insetY), bg, fg, 1, Imgproc.GC_INIT_WITH_RECT)
        val fgMask = Mat()
        val bytes = ByteArray(smallW * smallH)
        mask.get(0, 0, bytes)
        for (i in bytes.indices) bytes[i] = if (bytes[i].toInt() == Imgproc.GC_FGD || bytes[i].toInt() == Imgproc.GC_PR_FGD) 255.toByte() else 0
        fgMask.create(smallH, smallW, CvType.CV_8UC1)
        fgMask.put(0, 0, bytes)
        Imgproc.morphologyEx(fgMask, fgMask, Imgproc.MORPH_OPEN, Mat.ones(5, 5, CvType.CV_8U))
        Imgproc.morphologyEx(fgMask, fgMask, Imgproc.MORPH_CLOSE, Mat.ones(9, 9, CvType.CV_8U))
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(fgMask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        small.release(); mask.release(); bg.release(); fg.release(); fgMask.release()
        val contour = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
        val contourArea = Imgproc.contourArea(contour)
        val areaFraction = contourArea / max(1, smallW * smallH)
        if (areaFraction !in .06..0.88) return null
        val rect = Imgproc.minAreaRect(MatOfPoint2f(*contour.toArray()))
        val box = Array(4) { Point() }
        rect.points(box)
        val boxArea = Imgproc.contourArea(MatOfPoint2f(*box))
        if (boxArea <= 0 || contourArea / boxArea < .60) return null
        val sides = (0 until 4).map { i -> dist(box[i], box[(i + 3) % 4]) }
        if (sides.max() / max(sides.min(), 1.0) > 5.0) return null
        val cx = box.sumOf { it.x } / 4 / smallW
        val cy = box.sumOf { it.y } / 4 / smallH
        if (hypot(cx - .5, cy - .5) > .35) return null
        val sx = width.toDouble() / smallW
        val sy = height.toDouble() / smallH
        return ordered(box.toList()).map { Point(it.x * sx, it.y * sy) }
    }

    /** `vision._ordered`: angular order around the centre, top-left (min x+y) first. */
    private fun ordered(points: List<Point>): List<Point> {
        val cx = points.sumOf { it.x } / 4
        val cy = points.sumOf { it.y } / 4
        val byAngle = points.sortedBy { atan2(it.y - cy, it.x - cx) }
        val start = byAngle.indices.minBy { byAngle[it].x + byAngle[it].y }
        return List(4) { byAngle[(start + it) % 4] }
    }

    // ------------------------------------------------------------------ recipient ROI

    /**
     * `adapter.build_hypotheses(scheme=roi_foreground, margin=0)` + `features_masked(centres)`
     * on the 2:3 frame and its localizer mask (1 = foreground). A null mask
     * means no hypothesis: the whole frame, unfiltered.
     */
    fun recipientFeatures(frame: Mat, mask: ByteArray?): Pair<Features, MaskQuality> {
        if (mask == null) return gridFeatures(frame) to MaskQuality(0)
        val w = frame.cols()
        val h = frame.rows()
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) if (mask[y * w + x].toInt() != 0) {
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        val maskMat = Mat(h, w, CvType.CV_8UC1).apply { put(0, 0, mask) }
        val quality = MaskQuality(components(maskMat))
        if (x1 < 0 || x1 + 1 - x0 < 2 || y1 + 1 - y0 < 2) {
            maskMat.release()
            return gridFeatures(frame) to quality
        }
        val roiRect = Rect(x0, y0, x1 + 1 - x0, y1 + 1 - y0)
        val roi = Mat(frame, roiRect)
        val localMask = Mat(maskMat, roiRect)
        val roiFeatures = gridFeatures(roi, localMask)
        val features = FrozenMatcherS3.Features(roiFeatures.xy, roiFeatures.descriptors, roiFeatures.width, roiFeatures.height, x0, y0, w, h)
        maskMat.release()
        return features to quality
    }

    private fun components(mask: Mat): Int {
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val count = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8)
        labels.release(); stats.release(); centroids.release()
        return max(0, count - 1)
    }

    // ------------------------------------------------------------------ retrieval

    /** Frozen visual vocabulary with IDF; histograms are sqrt(counts) x idf, L2-normalised. */
    class Vocabulary(centers: FloatArray, private val idf: FloatArray) {
        val words: Int = idf.size
        private val centerMat = Mat(words, 128, CvType.CV_32F).apply { put(0, 0, centers) }

        fun histogram(features: Features, query: Boolean): FloatArray {
            val counts = DoubleArray(words)
            if (features.count > 0) {
                val rooted = root(features.descriptors, features.count)
                val matcher = BFMatcher.create(Core.NORM_L2, false)
                val pairs = ArrayList<MatOfDMatch>()
                matcher.knnMatch(rooted, centerMat, pairs, 2)
                java.lang.ref.Reference.reachabilityFence(matcher)
                for (pair in pairs) {
                    for (m in pair.toArray()) {
                        // FLANN reports squared L2; the soft weight is defined on that.
                        val squared = m.distance.toDouble() * m.distance
                        counts[m.trainIdx] += if (query) exp(-squared / .05) else 1.0
                    }
                    pair.release()
                }
                rooted.release()
            }
            val out = FloatArray(words) { (sqrt(counts[it]) * idf[it]).toFloat() }
            var norm = 0.0
            for (v in out) norm += v.toDouble() * v
            norm = max(sqrt(norm), 1e-12)
            for (i in out.indices) out[i] = (out[i] / norm).toFloat()
            return out
        }
    }

    class Reference(val id: String, val features: Features, val histogram: FloatArray) {
        internal val rooted: Mat by lazy { root(features.descriptors, features.count) }
    }

    fun reference(id: String, features: Features, vocabulary: Vocabulary) =
        Reference(id, features, vocabulary.histogram(features, query = false))

    // ------------------------------------------------------------------ matching

    fun match(query: Features, references: List<Reference>, vocabulary: Vocabulary, components: Int): Decision {
        require(references.isNotEmpty()) { "no references" }
        val qh = vocabulary.histogram(query, query = true)
        val scores = references.map { r -> r.histogram.indices.sumOf { r.histogram[it].toDouble() * qh[it] } }
        val shortlist = references.indices.sortedWith(compareByDescending<Int> { scores[it] }).take(min(TOP_K, references.size))
        val qRooted = if (query.count >= 4) root(query.descriptors, query.count) else null
        val results = shortlist.map { i -> verify(query, qRooted, references[i], scores[i]) }
        qRooted?.release()
        val ranked = results.withIndex().sortedWith(compareByDescending<IndexedValue<Candidate>> { it.value.finalScore }.thenBy { it.index }).map { it.value }
        val best = ranked.first()
        val runner = ranked.getOrNull(1)?.finalScore ?: 0.0
        val separation = best.finalScore / max(runner, 1.0)
        val baseline = best.finalScore >= THRESHOLD && separation >= MARGIN && best.inliers >= 12 &&
            best.coverage >= .04 && best.inlierRatio >= .3
        val gate = StrictCombinedGate.decide(
            baseline,
            StrictCombinedEvidence(best.geometryVerified, best.finalScore, best.inlierRatio, best.coverage, components),
        )
        val accepted = gate is StrictCombinedDecision.Keep
        return Decision(
            baselineAccepted = baseline,
            accepted = accepted,
            id = if (accepted) best.id else null,
            best = best,
            runnerUpScore = runner,
            separation = separation,
            gateReject = (gate as? StrictCombinedDecision.Reject)?.reason,
            candidates = ranked,
        )
    }

    /** `vision.verify` + `engine.projected_bbox_outside`. */
    private fun verify(query: Features, qRooted: Mat?, ref: Reference, retrievalScore: Double): Candidate {
        fun empty(reason: String, matches: Int = 0) =
            Candidate(ref.id, retrievalScore, 0.0, 0.0, 0, matches, 0.0, 0.0, false, reason, false)
        if (qRooted == null || min(query.count, ref.features.count) < 4) return empty("insufficient_features")
        val matcher = BFMatcher.create(Core.NORM_L2, false)
        val pairs = ArrayList<MatOfDMatch>()
        matcher.knnMatch(ref.rooted, qRooted, pairs, 2)
        java.lang.ref.Reference.reachabilityFence(matcher)
        val good = ArrayList<DMatch>()
        for (pair in pairs) {
            val two = pair.toArray()
            pair.release()
            if (two.size == 2 && two[0].distance < RATIO * two[1].distance) good.add(two[0])
        }
        // Unique query points: repeated patterns must not inflate evidence.
        val used = HashSet<Long>()
        val unique = ArrayList<DMatch>()
        for (m in good.sortedBy { it.distance }) {
            val qx = Math.rint(query.xy[2 * m.trainIdx].toDouble()).toLong()
            val qy = Math.rint(query.xy[2 * m.trainIdx + 1].toDouble()).toLong()
            if (used.add(qx * 1_000_003L + qy)) unique.add(m)
        }
        if (unique.size < 6) return empty("too_few_ratio_matches", unique.size)
        val a = unique.map { Point(ref.features.xy[2 * it.queryIdx].toDouble(), ref.features.xy[2 * it.queryIdx + 1].toDouble()) }
        val b = unique.map { Point(query.xy[2 * it.trainIdx].toDouble(), query.xy[2 * it.trainIdx + 1].toDouble()) }
        val src = MatOfPoint2f(*a.toTypedArray())
        val dst = MatOfPoint2f(*b.toTypedArray())
        val inlierMask = Mat()
        Core.setRNGSeed(42)
        val homography = Calib3d.findHomography(src, dst, Calib3d.USAC_MAGSAC, 4.0, inlierMask, 3000, .999)
        src.release(); dst.release()
        if (homography.empty() || inlierMask.empty()) return empty("homography_failed", unique.size)
        val flags = ByteArray(inlierMask.rows() * inlierMask.cols())
        inlierMask.get(0, 0, flags)
        inlierMask.release()
        val kept = a.filterIndexed { i, _ -> flags[i].toInt() != 0 }
        val n = kept.size
        if (n < 4) return empty("too_few_homography_inliers", unique.size)
        val coverage = abs(shoelace(convexHull(kept))) / (ref.features.width.toDouble() * ref.features.height)
        val left = kept.minOf { it.x }; val top = kept.minOf { it.y }
        val right = kept.maxOf { it.x }; val bottom = kept.maxOf { it.y }
        val corners = MatOfPoint2f(Point(left, top), Point(right, top), Point(right, bottom), Point(left, bottom))
        val projected = MatOfPoint2f()
        Core.perspectiveTransform(corners, projected, homography)
        val card = MatOfPoint2f(
            Point(0.0, 0.0), Point(ref.features.width.toDouble(), 0.0),
            Point(ref.features.width.toDouble(), ref.features.height.toDouble()), Point(0.0, ref.features.height.toDouble()),
        )
        val cardProjected = MatOfPoint2f()
        Core.perspectiveTransform(card, cardProjected, homography)
        val cardInFrame = cardProjected.toArray().map { Point(it.x + query.originX, it.y + query.originY) }
        card.release(); cardProjected.release()
        val frameCentre = Point(query.frameWidth / 2.0, query.frameHeight / 2.0)
        val cardValid = cardInFrame.all { it.x.isFinite() && it.y.isFinite() } && convex(cardInFrame)
        val centred = cardValid && contains(cardInFrame, frameCentre)
        val centreDistance = when {
            !cardValid -> Double.POSITIVE_INFINITY
            centred -> 0.0
            else -> cardInFrame.indices.minOf { i -> segmentDistance(frameCentre, cardInFrame[i], cardInFrame[(i + 1) % 4]) } /
                hypot(query.frameWidth.toDouble(), query.frameHeight.toDouble())
        }
        homography.release(); corners.release()
        val poly = projected.toArray()
        projected.release()
        if (poly.any { !it.x.isFinite() || !it.y.isFinite() } || !convex(poly.toList())) {
            return empty("invalid_projected_bbox", unique.size)
        }
        val area = Imgproc.contourArea(MatOfPoint2f(*poly), true)

        val lengths = (0 until 4).map { i -> dist(poly[i], poly[(i + 3) % 4]) }
        if (area < 300 || lengths.max() / max(lengths.min(), 1.0) > 8) return empty("degenerate_projected_bbox", unique.size)
        val ratio = n.toDouble() / unique.size
        val score = n * min(1.0, coverage / .20) * min(1.0, ratio / .5)
        val outside = poly.any { p ->
            p.x < -.25 * query.width || p.x > 1.25 * query.width || p.y < -.25 * query.height || p.y > 1.25 * query.height
        }
        return Candidate(
            id = ref.id,
            retrievalScore = retrievalScore,
            rawScore = score,
            finalScore = if (outside) 0.0 else score,
            inliers = n,
            matches = unique.size,
            inlierRatio = round4(ratio),
            coverage = round4(coverage),
            geometryVerified = true,
            geometryReason = "verified",
            vetoed = outside,
            coversFrameCentre = centred,
            centreDistance = centreDistance,
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

    private fun cross(o: Point, a: Point, b: Point) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    /** Andrew's monotone chain on exact coordinates (cv2.convexHull on float32 points). */
    private fun convexHull(points: List<Point>): List<Point> {
        val sorted = points.distinctBy { it.x to it.y }.sortedWith(compareBy<Point> { it.x }.thenBy { it.y })
        if (sorted.size < 3) return sorted
        val lower = ArrayList<Point>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) lower.removeAt(lower.size - 1)
            lower.add(p)
        }
        val upper = ArrayList<Point>()
        for (p in sorted.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) upper.removeAt(upper.size - 1)
            upper.add(p)
        }
        return lower.dropLast(1) + upper.dropLast(1)
    }

    private fun shoelace(points: List<Point>): Double {
        var sum = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum / 2.0
    }

    private fun segmentDistance(p: Point, a: Point, b: Point): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = dx * dx + dy * dy
        val t = if (len == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    /** Point inside a convex polygon (either winding). */
    private fun contains(polygon: List<Point>, p: Point): Boolean {
        var sign = 0
        for (i in polygon.indices) {
            val c = cross(polygon[i], polygon[(i + 1) % polygon.size], p)
            val s = if (c > 0) 1 else if (c < 0) -1 else 0
            if (s != 0) {
                if (sign != 0 && s != sign) return false
                sign = s
            }
        }
        return true
    }

    /** cv2.isContourConvex for a simple polygon: every turn has the same sign. */
    private fun convex(points: List<Point>): Boolean {
        var sign = 0
        for (i in points.indices) {
            val c = cross(points[i], points[(i + 1) % points.size], points[(i + 2) % points.size])
            val s = if (c > 0) 1 else if (c < 0) -1 else 0
            if (s != 0) {
                if (sign != 0 && s != sign) return false
                sign = s
            }
        }
        return sign != 0
    }

    /** Python `round()`: half to even. */
    internal fun pyRound(value: Double): Int = Math.rint(value).toInt()

    private fun round4(value: Double): Double = Math.rint(value * 10_000.0) / 10_000.0
}
