package dev.hryshyn.remanence.create

import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.capture.FrontCaptureFlow
import dev.hryshyn.remanence.capture.CaptureDiagnostic
import dev.hryshyn.remanence.capture.CaptureDiagnosticStage
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor

import dev.hryshyn.remanence.core.recognition.CaptureQualityGate
import dev.hryshyn.remanence.core.recognition.CaptureQualityInput
import dev.hryshyn.remanence.core.recognition.CaptureQualityMeter
import dev.hryshyn.remanence.core.recognition.CaptureAdmissionProfile
import dev.hryshyn.remanence.core.recognition.FingerprintCodec
import dev.hryshyn.remanence.core.recognition.FingerprintExtractor
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.PerspectiveWarper
import dev.hryshyn.remanence.core.recognition.PostcardContourDetector
import dev.hryshyn.remanence.core.recognition.PostcardCropSelector
import dev.hryshyn.remanence.core.recognition.QuadCandidate
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.core.recognition.StillCapturePipeline

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
    private val side: FingerprintSide,
    private val admissionProfile: CaptureAdmissionProfile = CaptureAdmissionProfile.calibratedM2(),
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
    private val gate = CaptureQualityGate(profile, admissionProfile)
    private val extractor = FingerprintExtractor(profile)

    override fun process(jpegBytes: ByteArray): ProcessedStill {
        val working = try {
            pipeline.process(jpegBytes)
        } catch (_: IllegalArgumentException) {
            return ProcessedStill.Rejected(
                setOf(dev.hryshyn.remanence.core.recognition.QualityReason.CROP_UNCERTAIN),
                diagnostic = diagnostic(CaptureDiagnosticStage.DECODE),
            )
        }
        working.use {
            val pixels = it.copyArgbPixels()

            val selection = try {
                cropSelector.select(
                    candidates = detectContours(pixels, it.width, it.height),
                    frameWidth = it.width,
                    frameHeight = it.height,
                )
            } catch (_: IllegalArgumentException) {
                return rejected(
                    setOf(dev.hryshyn.remanence.core.recognition.QualityReason.CROP_UNCERTAIN),
                    diagnostic(CaptureDiagnosticStage.CROP),
                )
            }
            val candidate = selection.candidate
            val warped = try {
                warper.warp(pixels, it.width, it.height, candidate.corners)
            } catch (_: IllegalArgumentException) {
                return rejected(
                    setOf(dev.hryshyn.remanence.core.recognition.QualityReason.CROP_UNCERTAIN),
                    diagnostic(
                        stage = CaptureDiagnosticStage.WARP,
                        usedGuideFallback = selection.usedGuideFallback,
                    ),
                )
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
                side,
            )
            if (reasons.isNotEmpty()) {
                return rejected(
                    reasons,
                    diagnostic(
                        stage = CaptureDiagnosticStage.QUALITY,
                        signals = signals,
                        usedGuideFallback = selection.usedGuideFallback,
                        warpedWidth = warped.width,
                        warpedHeight = warped.height,
                    ),
                )
            }

            val fingerprint = extractor.extract(
                warpedArgb = warped.pixels,
                width = warped.width,
                height = warped.height,
            )

            if (fingerprint.keypoints.isEmpty() ||
                fingerprint.descriptors.size != fingerprint.keypoints.size
            ) {
                return rejected(
                    setOf(dev.hryshyn.remanence.core.recognition.QualityReason.FEATURES_INSUFFICIENT),
                    diagnostic(
                        stage = CaptureDiagnosticStage.ORB,
                        signals = signals,
                        usedGuideFallback = selection.usedGuideFallback,
                        warpedWidth = warped.width,
                        warpedHeight = warped.height,
                        orbKeypoints = fingerprint.keypoints.size,
                        orbDescriptors = fingerprint.descriptors.size,
                    ),
                )
            }

            return ProcessedStill.Accepted(
                profileId = fingerprint.profileId,
                serializedBytes = FingerprintCodec.serialize(fingerprint),
            )
        }
    }

    private fun rejected(
        reasons: Set<dev.hryshyn.remanence.core.recognition.QualityReason>,
        diagnostic: CaptureDiagnostic? = null,
    ): ProcessedStill = ProcessedStill.Rejected(reasons, diagnostic)

    private fun diagnostic(
        stage: CaptureDiagnosticStage,
        signals: dev.hryshyn.remanence.core.recognition.CaptureQualitySignals? = null,
        usedGuideFallback: Boolean? = null,
        warpedWidth: Int? = null,
        warpedHeight: Int? = null,
        orbKeypoints: Int? = null,
        orbDescriptors: Int? = null,
    ): CaptureDiagnostic? = if (BuildConfig.DEBUG) {
        CaptureDiagnostic(
            side = side,
            stage = stage,
            laplacianThreshold = admissionProfile.minLaplacianVariance(side),
            laplacianVariance = signals?.laplacianVariance,
            nearBlackFraction = signals?.nearBlackFraction,
            clippedWhiteFraction = signals?.clippedWhiteFraction,
            largestGlareFraction = signals?.largestGlareFraction,
            usedGuideFallback = usedGuideFallback,
            warpedWidth = warpedWidth,
            warpedHeight = warpedHeight,
            orbKeypoints = orbKeypoints,
            orbDescriptors = orbDescriptors,
        )
    } else {
        null
    }

}
