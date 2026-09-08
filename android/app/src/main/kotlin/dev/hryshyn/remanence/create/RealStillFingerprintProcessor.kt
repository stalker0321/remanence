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
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.LocalizationFeatureConfig
import dev.hryshyn.remanence.core.recognition.LocalizationProposalSelector
import dev.hryshyn.remanence.core.recognition.LocalizationProposalSource
import dev.hryshyn.remanence.core.recognition.OpenCvUnavailableException
import dev.hryshyn.remanence.core.recognition.PerspectiveWarper
import dev.hryshyn.remanence.core.recognition.PostcardContourDetector
import dev.hryshyn.remanence.core.recognition.PostcardCropSelector
import dev.hryshyn.remanence.core.recognition.QuadCandidate
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec
import dev.hryshyn.remanence.core.recognition.SiftRootSiftFingerprintExtractor
import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprint
import dev.hryshyn.remanence.core.recognition.StillCapturePipeline
import dev.hryshyn.remanence.core.recognition.V2LinePostcardLocator
import kotlin.coroutines.cancellation.CancellationException

/**
 * FIX-M1-007-11: the REAL capture processor behind the create flow's
 * StillProcessor port. Runs the production pipeline end to end - bounded
 * decode, proposal selection, aspect-preserving warp, advisory quality
 * telemetry, and `postcard-sift-rootsift-v1` extraction - then returns serialized bytes.
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
    fingerprintExtractor: ((IntArray, Int, Int) -> SiftRootSiftFingerprint)? = null,
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
    private val extractor = SiftRootSiftFingerprintExtractor(profile.sift)
    private val extractFingerprint = fingerprintExtractor ?: { pixels: IntArray, width: Int, height: Int ->
        extractor.extract(pixels, width, height)
    }

    override fun process(jpegBytes: ByteArray): ProcessedStill = try {
        processInternal(jpegBytes)
    } catch (_: OpenCvUnavailableException) {
        unavailableFeatureFailure()
    } catch (_: UnsatisfiedLinkError) {
        // Native linkage can fail from any OpenCV stage, not only SIFT. Map
        // only this expected linkage failure; fatal VM errors still escape.
        unavailableFeatureFailure()
    }

    private fun processInternal(jpegBytes: ByteArray): ProcessedStill {
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
                    try {
                        v2Locator.detect(pixels, it.width, it.height)
                    } catch (failure: OpenCvUnavailableException) {
                        throw failure
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        null
                    }
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
                try {
                    selection to warper.warp(pixels, it.width, it.height, selection.candidate.corners)
                } catch (failure: OpenCvUnavailableException) {
                    throw failure
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    if (selection.proposalSource == LocalizationProposalSource.V2_LINE) {
                        fallbackReason = LocalizationFallbackReason.V2_WARP_INVALID
                    }
                    null
                }
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
            var fingerprint: SiftRootSiftFingerprint? = null
            try {
                fingerprint = try {
                    extractFingerprint(warped.pixels, warped.width, warped.height)
                } catch (_: OpenCvUnavailableException) {
                    // Native linkage/availability is a typed ordinary capture
                    // refusal; it must not escape as an unchecked LinkageError.
                    return featureFailure(
                        selection = selection,
                        fallbackReason = fallbackReason,
                        signals = signals,
                        warpedWidth = warped.width,
                        warpedHeight = warped.height,
                    )
                } catch (_: IllegalArgumentException) {
                    // Empty/no-usable SIFT output is a hard capture failure, but
                    // it remains a normal capture outcome for the UI.
                    return featureFailure(
                        selection = selection,
                        fallbackReason = fallbackReason,
                        signals = signals,
                        warpedWidth = warped.width,
                        warpedHeight = warped.height,
                    )
                }
                val extracted = requireNotNull(fingerprint)
                if (extracted.keypoints.isEmpty() ||
                    extracted.quantizedSiftDescriptors.size != extracted.keypoints.size
                ) {
                    observeCapture(
                        source = selection.proposalSource,
                        outcome = CaptureLocalizationOutcome.REJECTED,
                        stage = CaptureDiagnosticStage.FEATURES,
                        fallbackReason = fallbackReason,
                        qualityReasons = setOf(dev.hryshyn.remanence.core.recognition.QualityReason.FEATURES_INSUFFICIENT),
                        featureCount = extracted.keypoints.size,
                    )
                    return rejected(
                        setOf(dev.hryshyn.remanence.core.recognition.QualityReason.FEATURES_INSUFFICIENT),
                        diagnostic(
                            stage = CaptureDiagnosticStage.FEATURES,
                            signals = signals,
                            usedGuideFallback = selection.usedGuideFallback,
                            warpedWidth = warped.width,
                            warpedHeight = warped.height,
                            featureKeypoints = extracted.keypoints.size,
                            featureDescriptors = extracted.quantizedSiftDescriptors.size,
                        ),
                    )
                }

                observeCapture(
                    source = selection.proposalSource,
                    outcome = CaptureLocalizationOutcome.ACCEPTED,
                    stage = CaptureDiagnosticStage.FEATURES,
                    fallbackReason = fallbackReason,
                    qualityReasons = reasons,
                    featureCount = extracted.keypoints.size,
                )

                val profileId = extracted.profileId
                val serialized = SiftRootSiftFingerprintCodec.serialize(extracted)
                return ProcessedStill.Accepted(
                    profileId = profileId,
                    serializedBytes = serialized,
                    advisoryQualityReasons = reasons,
                )
            } finally {
                fingerprint?.wipe()
            }
        }
    }

    private fun rejected(
        reasons: Set<dev.hryshyn.remanence.core.recognition.QualityReason>,
        diagnostic: CaptureDiagnostic? = null,
    ): ProcessedStill = ProcessedStill.Rejected(reasons, diagnostic)

    private fun unavailableFeatureFailure(): ProcessedStill {
        observeCapture(
            source = LocalizationProposalSource.NONE,
            outcome = CaptureLocalizationOutcome.REJECTED,
            stage = CaptureDiagnosticStage.FEATURES,
            qualityReasons = setOf(dev.hryshyn.remanence.core.recognition.QualityReason.FEATURES_INSUFFICIENT),
            featureCount = 0,
        )
        return rejected(
            setOf(dev.hryshyn.remanence.core.recognition.QualityReason.FEATURES_INSUFFICIENT),
            diagnostic(CaptureDiagnosticStage.FEATURES, featureKeypoints = 0, featureDescriptors = 0),
        )
    }

    private fun featureFailure(
        selection: dev.hryshyn.remanence.core.recognition.PostcardCropSelection,
        fallbackReason: LocalizationFallbackReason,
        signals: dev.hryshyn.remanence.core.recognition.CaptureQualitySignals,
        warpedWidth: Int,
        warpedHeight: Int,
    ): ProcessedStill {
        observeCapture(
            source = selection.proposalSource,
            outcome = CaptureLocalizationOutcome.REJECTED,
            stage = CaptureDiagnosticStage.FEATURES,
            fallbackReason = fallbackReason,
            qualityReasons = setOf(dev.hryshyn.remanence.core.recognition.QualityReason.FEATURES_INSUFFICIENT),
            featureCount = 0,
        )
        return rejected(
            setOf(dev.hryshyn.remanence.core.recognition.QualityReason.FEATURES_INSUFFICIENT),
            diagnostic(
                stage = CaptureDiagnosticStage.FEATURES,
                signals = signals,
                usedGuideFallback = selection.usedGuideFallback,
                warpedWidth = warpedWidth,
                warpedHeight = warpedHeight,
                featureKeypoints = 0,
                featureDescriptors = 0,
            ),
        )
    }

    /** Diagnostic observer is isolated from the capture result and never authoritative. */
    private fun observeCapture(
        source: LocalizationProposalSource,
        outcome: CaptureLocalizationOutcome,
        stage: CaptureDiagnosticStage,
        fallbackReason: LocalizationFallbackReason = LocalizationFallbackReason.NONE,
        qualityReasons: Set<dev.hryshyn.remanence.core.recognition.QualityReason> = emptySet(),
        featureCount: Int? = null,
    ) {
        try {
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
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // Diagnostics are advisory, but fatal/linkage errors must remain
            // visible to the outer typed capture boundary.
        }
    }

    private fun diagnostic(
        stage: CaptureDiagnosticStage,
        signals: dev.hryshyn.remanence.core.recognition.CaptureQualitySignals? = null,
        usedGuideFallback: Boolean? = null,
        warpedWidth: Int? = null,
        warpedHeight: Int? = null,
        featureKeypoints: Int? = null,
        featureDescriptors: Int? = null,
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
            featureKeypoints = featureKeypoints,
            featureDescriptors = featureDescriptors,
        )
    } else {
        null
    }

}
