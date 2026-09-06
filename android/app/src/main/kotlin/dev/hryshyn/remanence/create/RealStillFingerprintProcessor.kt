package dev.hryshyn.remanence.create

import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.capture.FrontCaptureFlow
import dev.hryshyn.remanence.capture.CaptureDiagnostic
import dev.hryshyn.remanence.capture.CaptureDiagnosticStage
import dev.hryshyn.remanence.capture.CaptureLocalizationDiagnostics
import dev.hryshyn.remanence.capture.CaptureLocalizationEvent
import dev.hryshyn.remanence.capture.CaptureLocalizationOutcome
import dev.hryshyn.remanence.capture.LocalizationFallbackReason
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import dev.hryshyn.remanence.core.recognition.CaptureAdmissionProfile
import dev.hryshyn.remanence.core.recognition.CaptureQualityGate
import dev.hryshyn.remanence.core.recognition.CaptureQualityInput
import dev.hryshyn.remanence.core.recognition.CaptureQualityMeter
import dev.hryshyn.remanence.core.recognition.FingerprintCodec
import dev.hryshyn.remanence.core.recognition.FingerprintExtractor
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.LocalizationFeatureConfig
import dev.hryshyn.remanence.core.recognition.LocalizationProposalSelector
import dev.hryshyn.remanence.core.recognition.LocalizationProposalSource
import dev.hryshyn.remanence.core.recognition.PerspectiveWarper
import dev.hryshyn.remanence.core.recognition.PostcardContourDetector
import dev.hryshyn.remanence.core.recognition.PostcardCropSelector
import dev.hryshyn.remanence.core.recognition.QuadCandidate
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.core.recognition.StillCapturePipeline
import dev.hryshyn.remanence.core.recognition.V2LinePostcardLocator

/**
 * FIX-M1-007-11: the REAL capture processor behind the create flow's
 * StillProcessor port. Runs the production pipeline end to end - bounded
 * decode, proposal selection, aspect-preserving warp, advisory quality
 * telemetry, and `mvp-orb-v1` extraction - then returns serialized bytes.
 * The v2 line locator is local-switch controlled and never receives saved
 * benchmark corners. Legacy contour selection and the guide remain fallback
 * paths. Weak matcher evidence, verification, and empty features remain hard
 * protections downstream.
 */
class RealStillFingerprintProcessor(
    private val profile: RecognitionProfile,
    private val side: FingerprintSide,
    private val admissionProfile: CaptureAdmissionProfile = CaptureAdmissionProfile.calibratedM2(),
    private val localizationConfig: LocalizationFeatureConfig = if (BuildConfig.REMANENCE_V2_LINE_LOCALIZATION) {
        LocalizationFeatureConfig.v2LineThenLegacy()
    } else {
        LocalizationFeatureConfig.legacyDefault()
    },
    contourDetector: ((IntArray, Int, Int) -> List<QuadCandidate>)? = null,
    private val captureDiagnosticObserver: ((CaptureLocalizationEvent) -> Unit)? =
        CaptureLocalizationDiagnostics::report,
) : StillProcessor {

    private val pipeline = StillCapturePipeline()
    private val detector = PostcardContourDetector(profile)
    private val v2Locator = V2LinePostcardLocator(profile)
    private val cropSelector = PostcardCropSelector(profile)
    private val proposalSelector = LocalizationProposalSelector(cropSelector)
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
            observeCapture(
                source = LocalizationProposalSource.NONE,
                outcome = CaptureLocalizationOutcome.REJECTED,
                stage = CaptureDiagnosticStage.DECODE,
                qualityReasons = setOf(dev.hryshyn.remanence.core.recognition.QualityReason.CROP_UNCERTAIN),
            )
            return ProcessedStill.Rejected(
                setOf(dev.hryshyn.remanence.core.recognition.QualityReason.CROP_UNCERTAIN),
                diagnostic = diagnostic(CaptureDiagnosticStage.DECODE),
            )
        }
        working.use {
            val pixels = it.copyArgbPixels()

            val attempts = try {
                val legacyCandidates = detectContours(pixels, it.width, it.height)
                val primaryCandidates = if (localizationConfig.usesV2LineLocator) {
                    runCatching { v2Locator.detect(pixels, it.width, it.height) }.getOrNull()
                } else {
                    null
                }
                proposalSelector.orderedAttempts(
                    primaryCandidates = primaryCandidates,
                    legacyCandidates = legacyCandidates,
                    frameWidth = it.width,
                    frameHeight = it.height,
                )
            } catch (_: IllegalArgumentException) {
                observeCapture(
                    source = LocalizationProposalSource.NONE,
                    outcome = CaptureLocalizationOutcome.REJECTED,
                    stage = CaptureDiagnosticStage.CROP,
                    qualityReasons = setOf(dev.hryshyn.remanence.core.recognition.QualityReason.CROP_UNCERTAIN),
                )
                return rejected(
                    setOf(dev.hryshyn.remanence.core.recognition.QualityReason.CROP_UNCERTAIN),
                    diagnostic(CaptureDiagnosticStage.CROP),
                )
            }
            var fallbackReason = if (
                localizationConfig.usesV2LineLocator &&
                attempts.none { it.proposalSource == LocalizationProposalSource.V2_LINE }
            ) {
                LocalizationFallbackReason.V2_NO_PROPOSAL
            } else {
                LocalizationFallbackReason.NONE
            }
            val selectedWarp = attempts.asSequence().mapNotNull { selection ->
                runCatching { selection to warper.warp(pixels, it.width, it.height, selection.candidate.corners) }
                    .onFailure {
                        if (selection.proposalSource == LocalizationProposalSource.V2_LINE) {
                            fallbackReason = LocalizationFallbackReason.V2_WARP_INVALID
                        }
                    }
                    .getOrNull()
            }.firstOrNull()
            if (selectedWarp == null) {
                observeCapture(
                    source = LocalizationProposalSource.NONE,
                    outcome = CaptureLocalizationOutcome.REJECTED,
                    stage = CaptureDiagnosticStage.WARP,
                    fallbackReason = fallbackReason,
                    qualityReasons = setOf(dev.hryshyn.remanence.core.recognition.QualityReason.CROP_UNCERTAIN),
                )
                return rejected(
                    setOf(dev.hryshyn.remanence.core.recognition.QualityReason.CROP_UNCERTAIN),
                    diagnostic(
                        stage = CaptureDiagnosticStage.WARP,
                        usedGuideFallback = attempts.lastOrNull()?.usedGuideFallback,
                    ),
                )
            }
            val selection = selectedWarp.first
            val warped = selectedWarp.second
            val candidate = selection.candidate
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
            val fingerprint = extractor.extract(
                warpedArgb = warped.pixels,
                width = warped.width,
                height = warped.height,
            )

            if (fingerprint.keypoints.isEmpty() ||
                fingerprint.descriptors.size != fingerprint.keypoints.size
            ) {
                observeCapture(
                    source = selection.proposalSource,
                    outcome = CaptureLocalizationOutcome.REJECTED,
                    stage = CaptureDiagnosticStage.ORB,
                    fallbackReason = fallbackReason,
                    qualityReasons = setOf(dev.hryshyn.remanence.core.recognition.QualityReason.FEATURES_INSUFFICIENT),
                    featureCount = fingerprint.keypoints.size,
                )
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

            observeCapture(
                source = selection.proposalSource,
                outcome = CaptureLocalizationOutcome.ACCEPTED,
                stage = CaptureDiagnosticStage.ORB,
                fallbackReason = fallbackReason,
                qualityReasons = reasons,
                featureCount = fingerprint.keypoints.size,
            )

            return ProcessedStill.Accepted(
                profileId = fingerprint.profileId,
                serializedBytes = FingerprintCodec.serialize(fingerprint),
                advisoryQualityReasons = reasons,
            )
        }
    }

    private fun rejected(
        reasons: Set<dev.hryshyn.remanence.core.recognition.QualityReason>,
        diagnostic: CaptureDiagnostic? = null,
    ): ProcessedStill = ProcessedStill.Rejected(reasons, diagnostic)

    /** Diagnostic observer is isolated from the capture result and never authoritative. */
    private fun observeCapture(
        source: LocalizationProposalSource,
        outcome: CaptureLocalizationOutcome,
        stage: CaptureDiagnosticStage,
        fallbackReason: LocalizationFallbackReason = LocalizationFallbackReason.NONE,
        qualityReasons: Set<dev.hryshyn.remanence.core.recognition.QualityReason> = emptySet(),
        featureCount: Int? = null,
    ) {
        runCatching {
            captureDiagnosticObserver?.invoke(
                CaptureLocalizationEvent(
                    side = side,
                    featureV2Enabled = localizationConfig.usesV2LineLocator,
                    source = source,
                    stage = stage,
                    outcome = outcome,
                    fallbackReason = fallbackReason,
                    qualityReasons = qualityReasons,
                    featureCount = featureCount,
                ),
            )
        }
    }

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
