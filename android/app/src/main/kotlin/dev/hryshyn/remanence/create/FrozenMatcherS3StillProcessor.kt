package dev.hryshyn.remanence.create

import android.graphics.Bitmap
import dev.hryshyn.remanence.capture.CapturedPostcard
import dev.hryshyn.remanence.capture.OpeningQuad
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import dev.hryshyn.remanence.core.model.FrozenMatcherS3FingerprintCodec
import dev.hryshyn.remanence.core.recognition.FrameGeometry
import dev.hryshyn.remanence.core.recognition.FrozenLocalizer
import dev.hryshyn.remanence.core.recognition.FrozenLocalizerSession
import dev.hryshyn.remanence.core.recognition.FrozenMatcherS3
import dev.hryshyn.remanence.core.recognition.FrozenMatcherS3Fingerprint
import dev.hryshyn.remanence.core.recognition.MaskQuad
import dev.hryshyn.remanence.core.recognition.OrtLocalizerRuntime
import dev.hryshyn.remanence.core.recognition.PointD
import dev.hryshyn.remanence.core.recognition.QualityReason
import dev.hryshyn.remanence.core.recognition.StillCapturePipeline
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * FRONT capture through the frozen postcard matcher (S3).
 *
 * [Role.SENDER] describes the card a capsule is created for: GrabCut
 * auto-crop (or the whole photo) and grid SIFT. [Role.RECIPIENT] describes a
 * scan: the centred 2:3 frame, the ONNX visible-card mask, and grid SIFT on the
 * mask's ROI. Both decode to the frozen 960 px working size.
 */
class FrozenMatcherS3StillProcessor(
    private val role: Role,
    private val modelBytes: () -> ByteArray? = { null },
    /** Recipient only: also return the photographed card for the opening animation. */
    private val captureOpeningPostcard: Boolean = false,
) : StillProcessor {
    enum class Role { SENDER, RECIPIENT }

    private val pipeline = StillCapturePipeline()
    private var session: FrozenLocalizerSession? = null

    /** Loads the localizer ahead of the first capture (model read + ONNX session). */
    @Synchronized
    fun warmUp() {
        if (role != Role.RECIPIENT || session != null) return
        val started = System.nanoTime()
        session = modelBytes()?.let { bytes -> runCatching { FrozenLocalizerSession(OrtLocalizerRuntime.open(bytes)) }.getOrNull() }
        log("warm-up ${(System.nanoTime() - started) / 1_000_000} ms, ready=${session != null}")
    }

    @Synchronized
    override fun process(jpegBytes: ByteArray): ProcessedStill {
        val started = System.nanoTime()
        try {
            return processTimed(jpegBytes)
        } finally {
            log("$role capture ${(System.nanoTime() - started) / 1_000_000} ms")
        }
    }

    private fun processTimed(jpegBytes: ByteArray): ProcessedStill {
        val working = try {
            pipeline.process(jpegBytes)
        } catch (_: IllegalArgumentException) {
            return ProcessedStill.Rejected(setOf(QualityReason.CROP_UNCERTAIN))
        }
        return working.use { capture ->
            val pixels = capture.copyArgbPixels()
            val image = toBgr960(pixels, capture.width, capture.height)
            try {
                when (role) {
                    Role.SENDER -> sender(image)
                    Role.RECIPIENT -> recipient(image, pixels, capture.width, capture.height)
                }
            } finally {
                image.release()
            }
        }
    }

    private fun sender(image: Mat): ProcessedStill {
        val crop = FrozenMatcherS3.autoCrop(image)
        val features = try {
            FrozenMatcherS3.gridFeatures(crop)
        } finally {
            crop.release()
        }
        // The frozen index build refuses references with fewer than 12 features.
        if (features.count < MIN_SENDER_FEATURES) {
            return ProcessedStill.Rejected(setOf(QualityReason.FEATURES_INSUFFICIENT))
        }
        return accepted(FrozenMatcherS3Fingerprint.toFingerprint(features), null)
    }

    private fun recipient(image: Mat, pixels: IntArray, width: Int, height: Int): ProcessedStill {
        val bounds = FrameGeometry.bounds(image.cols(), image.rows())
        val frame = Mat(image, Rect(bounds.x0, bounds.y0, bounds.x1 - bounds.x0, bounds.y1 - bounds.y0)).clone()
        try {
            val mask = localize(frame)
            val (features, quality) = FrozenMatcherS3.recipientFeatures(frame, mask)
            if (features.count < 1) return ProcessedStill.Rejected(setOf(QualityReason.FEATURES_INSUFFICIENT))
            val query = FrozenMatcherS3Fingerprint.QueryFrame(
                features.originX, features.originY, features.frameWidth, features.frameHeight, quality.components,
            )
            val opening = if (captureOpeningPostcard && mask != null) {
                openingPostcard(mask, frame.cols(), frame.rows(), bounds, image, pixels, width, height)
            } else {
                null
            }
            return accepted(FrozenMatcherS3Fingerprint.toFingerprint(features, query), opening)
        } finally {
            frame.release()
        }
    }

    private fun accepted(fingerprint: dev.hryshyn.remanence.core.model.SiftRootSiftFingerprint, opening: CapturedPostcard?): ProcessedStill {
        val bytes = try {
            FrozenMatcherS3FingerprintCodec.serialize(fingerprint)
        } finally {
            fingerprint.wipe()
        }
        return ProcessedStill.Accepted(
            profileId = FrozenMatcherS3FingerprintCodec.PROFILE_ID,
            serializedBytes = bytes,
            openingPostcard = opening,
        )
    }

    private fun localize(frame: Mat): ByteArray? {
        val active = session ?: modelBytes()?.let { bytes ->
            runCatching { FrozenLocalizerSession(OrtLocalizerRuntime.open(bytes)) }.getOrNull()
        }?.also { session = it } ?: return null
        val bgr = ByteArray(3 * frame.cols() * frame.rows())
        frame.get(0, 0, bgr)
        return when (val result = active.localize(bgr, frame.cols(), frame.rows())) {
            is FrozenLocalizer.LocalizerResult.Proposal -> result.mask
            is FrozenLocalizer.LocalizerResult.Unknown -> null
        }
    }

    /**
     * The card outline for the opening animation: a perspective quad from the
     * localizer mask (reference `mask_to_quad`), pulled in slightly so mask
     * bleed never shows the table. A card running past the 2:3 frame is
     * re-localized on the whole photo. Null unless it has postcard proportions.
     */
    private fun openingPostcard(
        mask: ByteArray,
        frameW: Int,
        frameH: Int,
        bounds: FrameGeometry.Bounds,
        image: Mat,
        pixels: IntArray,
        width: Int,
        height: Int,
    ): CapturedPostcard? = runCatching {
        var quad = MaskQuad.fromMask(mask, frameW, frameH)?.map { Point(it.x + bounds.x0, it.y + bounds.y0) }
            ?: return null
        val touchesFrame = quad.any { p ->
            p.x <= bounds.x0 + EDGE_PX || p.x >= bounds.x1 - EDGE_PX || p.y <= bounds.y0 + EDGE_PX || p.y >= bounds.y1 - EDGE_PX
        }
        if (touchesFrame) {
            localize(image)?.let { whole -> MaskQuad.fromMask(whole, image.cols(), image.rows()) }?.let { quad = it }
        }
        val toPhoto = width.toDouble() / image.cols()
        val ordered = OpeningQuad.order(MaskQuad.inset(quad, INSET).map { PointD(it.x * toPhoto, it.y * toPhoto) })
        if (OpeningQuad.aspect(ordered) !in OpeningQuad.MIN_ASPECT..OpeningQuad.MAX_ASPECT) return null
        val scale = minOf(1.0, OPENING_PHOTO_EDGE_PX / max(width, height))
        val full = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val photo = if (scale < 1.0) {
            Bitmap.createScaledBitmap(full, (width * scale).roundToInt(), (height * scale).roundToInt(), true).also { full.recycle() }
        } else {
            full
        }
        CapturedPostcard(photo, ordered.map { PointD(it.x * scale, it.y * scale) })
    }.getOrNull()

    private fun toBgr960(argb: IntArray, width: Int, height: Int): Mat {
        val bgr = ByteArray(width * height * 3)
        for (i in argb.indices) {
            val c = argb[i]
            bgr[3 * i] = c.toByte()
            bgr[3 * i + 1] = (c shr 8).toByte()
            bgr[3 * i + 2] = (c shr 16).toByte()
        }
        val full = Mat(height, width, CvType.CV_8UC3).apply { put(0, 0, bgr) }
        val scale = FrozenMatcherS3.DECODE_MAX_SIDE.toDouble() / max(width, height)
        if (scale >= 1.0) return full
        val out = Mat()
        Imgproc.resize(full, out, Size((width * scale).roundToInt().toDouble(), (height * scale).roundToInt().toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        full.release()
        return out
    }

    private fun log(message: String) {
        runCatching { android.util.Log.i("RemanenceS3", message) }
    }

    private companion object {
        const val MIN_SENDER_FEATURES = 12
        const val EDGE_PX = 3
        const val INSET = 0.015
        const val OPENING_PHOTO_EDGE_PX = 1280.0
    }
}
