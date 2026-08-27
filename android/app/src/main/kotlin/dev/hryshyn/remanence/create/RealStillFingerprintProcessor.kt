package dev.hryshyn.remanence.create

import dev.hryshyn.remanence.capture.FrontCaptureFlow
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor

import dev.hryshyn.remanence.core.recognition.CaptureQualityGate
import dev.hryshyn.remanence.core.recognition.CaptureQualityInput
import dev.hryshyn.remanence.core.recognition.CaptureQualityMeter
import dev.hryshyn.remanence.core.recognition.FingerprintCodec
import dev.hryshyn.remanence.core.recognition.FingerprintExtractor
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.PerspectiveWarper
import dev.hryshyn.remanence.core.recognition.PostcardContourDetector
import dev.hryshyn.remanence.core.recognition.PostcardCropSelector
import dev.hryshyn.remanence.core.recognition.QuadCandidate
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.core.recognition.StillCapturePipeline
import java.io.File

/**
 * FIX-M1-007-11: the REAL capture processor behind the create flow's
 * StillProcessor port. Runs the documented pipeline end to end - bounded
 * decode, contour detection, quality gates, aspect-preserving warp, and
 * `mvp-orb-v1` extraction - then returns the serialized fingerprint bytes.
 * A credible contour is preferred; when contour detection finds nothing, the
 * shared visible guide supplies a bounded central crop. Blur/exposure/glare
 * and usable-ORB gates still apply to both paths.
 */
class RealStillFingerprintProcessor(
    private val profile: RecognitionProfile,
    private val side: FingerprintSide = FingerprintSide.FRONT,
    contourDetector: ((IntArray, Int, Int) -> List<QuadCandidate>)? = null,
) : StillProcessor {

    private val pipeline = StillCapturePipeline()
    private val detector = PostcardContourDetector(profile)
    private val cropSelector = PostcardCropSelector(profile)
    private val detectContours = contourDetector ?: { pixels: IntArray, width: Int, height: Int ->
        detector.detect(pixels, width, height)
    }
    private val warper = PerspectiveWarper(profile)
    private val meter = CaptureQualityMeter()
    private val gate = CaptureQualityGate(profile)
    private val extractor = FingerprintExtractor(profile)

    override fun process(jpegBytes: ByteArray): ProcessedStill {
        val working = try {
            pipeline.process(jpegBytes)
        } catch (_: IllegalArgumentException) {
            return ProcessedStill.Rejected(
                setOf(dev.hryshyn.remanence.core.recognition.QualityReason.CROP_UNCERTAIN),
            )
        }
        working.use {
            val pixels = it.copyArgbPixels()

            val selection = cropSelector.select(
                candidates = detectContours(pixels, it.width, it.height),
                frameWidth = it.width,
                frameHeight = it.height,
            )
            val candidate = selection.candidate
            val warped = try {
                warper.warp(pixels, it.width, it.height, candidate.corners)
            } catch (_: IllegalArgumentException) {
                return rejected(setOf(dev.hryshyn.remanence.core.recognition.QualityReason.CROP_UNCERTAIN))
            }
            val signals = meter.measure(warped.pixels, warped.width, warped.height)
            val reasons = gate.evaluate(
                CaptureQualityInput(
                    signals = signals,
                    detectedAreaRatio = candidate.areaRatio,
                    rectangularity = candidate.rectangularity,
                    cropAspectRatio = warped.width.toDouble() / warped.height.toDouble(),
                    croppedShortEdgePx = minOf(warped.width, warped.height),
                ),
            )
            if (reasons.isNotEmpty()) return rejected(reasons)

            val fingerprint = extractor.extract(
                warpedArgb = warped.pixels,
                width = warped.width,
                height = warped.height,
                side = side,
            )

            if (fingerprint.keypoints.isEmpty() ||
                fingerprint.descriptors.size != fingerprint.keypoints.size
            ) {
                return rejected(setOf(dev.hryshyn.remanence.core.recognition.QualityReason.FEATURES_INSUFFICIENT))
            }

            return ProcessedStill.Accepted(
                profileId = fingerprint.profileId,
                serializedBytes = FingerprintCodec.serialize(fingerprint),
            )
        }
    }

    private fun rejected(reasons: Set<dev.hryshyn.remanence.core.recognition.QualityReason>): ProcessedStill =
        ProcessedStill.Rejected(reasons)

    companion object {
        /** Staging root for normalized photo plaintext during one create session. */
        fun stagingDirectory(filesRoot: File): File = File(filesRoot, "create-staging")
    }
}
