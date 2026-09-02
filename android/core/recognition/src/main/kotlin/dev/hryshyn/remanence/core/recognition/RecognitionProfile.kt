package dev.hryshyn.remanence.core.recognition

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Immutable, versioned recognition profile carrying every documented
 * threshold (docs/recognition.md). No threshold may exist as a code literal;
 * database/fingerprint records reference [profileId].
 *
 * FRONT-only production contract (ADR-012, M2-F0-01): composite weights and
 * back-specific thresholds are deleted; ranking is FRONT-only with explicitly
 * named FRONT thresholds preserving numeric values pending M3 calibration.
 */
data class RecognitionProfile(
    val formatVersion: Int,
    val profileId: String,
    val capture: CaptureGates,
    val orb: OrbExtraction,
    val match: MatchThresholds,
    val ranking: RankingThresholds,
) {
    @Serializable
    data class CaptureGates(
        val minCardAreaRatio: Double,
        val minShortEdgeAfterWarpPx: Int,
        val canonicalLongEdgePx: Int,
        val maxCornerOutsideFramePx: Int,
        val aspectRatioMin: Double,
        val aspectRatioMax: Double,
        val minLaplacianVariance: Double,
        val maxNearBlackFraction: Double,
        val maxClippedWhiteFraction: Double,
        val maxGlareRegionFraction: Double,
        val minRectangularity: Double = 0.80,
    )

    @Serializable
    data class OrbExtraction(
        val nfeatures: Int,
        val scaleFactor: Double,
        val nlevels: Int,
        val edgeThreshold: Int,
        val firstLevel: Int,
        val wtaK: Int,
        val scoreTypeHarris: Boolean,
        val patchSize: Int,
        val fastThreshold: Int,
    )

    @Serializable
    data class MatchThresholds(
        val inlierReprojectionTolerancePx: Double,
        val countScoreInliersDivisor: Double,
        val ratioScoreOffset: Double,
        val ratioScoreSpan: Double,
        val coverageScoreTarget: Double,
        val reprojectionScoreMaxMedianErrorPx: Double,
        val weakMinRatioMatches: Int,
        val weakMinInliers: Int,
        val weakMinInlierRatio: Double,
        val weakMinCoverage: Double,
        val weakMinGridCells: Int,
        val coverageGridSize: Int,
        val strongMinRatioMatches: Int,
        val strongMinInliers: Int,
        val strongMinInlierRatio: Double,
        val strongMinCoverage: Double,
        val strongMaxMedianErrorPx: Double,
        val homographyAreaRatioMin: Double,
        val homographyAreaRatioMax: Double,
        val homographyMaxOppositeEdgeRatio: Double,
    )

    @Serializable
    data class RankingThresholds(
        val duplicateFrontMargin: Double,
        val autoFrontMin: Double,
        val autoMarginOverRunnerUp: Double,
        val duplicateFrontMinScore: Double,
        val chooserFrontMin: Double,
        val confidenceAreaWeight: Double = 0.40,
        val confidenceRectangularityWeight: Double = 0.30,
        val confidenceEdgeSupportWeight: Double = 0.20,
        val confidenceGuideProximityWeight: Double = 0.10,
        val minContourConfidence: Double,
    )

    companion object {

        const val FORMAT_VERSION_1: Int = 1
        const val MVP_ORB_V1_ID: String = "mvp-orb-v1"

        private val strictJson = Json {
            ignoreUnknownKeys = false
            isLenient = false
            encodeDefaults = true
        }

        /** Seed values exactly as documented for `mvp-orb-v1` (docs/recognition.md sections 4, 6–9). */
        fun mvpOrbV1(): RecognitionProfile = RecognitionProfile(
            formatVersion = FORMAT_VERSION_1,
            profileId = MVP_ORB_V1_ID,
            capture = CaptureGates(
                minCardAreaRatio = 0.35,
                minShortEdgeAfterWarpPx = 600,
                canonicalLongEdgePx = 1600,
                maxCornerOutsideFramePx = 0,
                aspectRatioMin = 1.15,
                aspectRatioMax = 2.20,
                minLaplacianVariance = 80.0,
                maxNearBlackFraction = 0.25,
                maxClippedWhiteFraction = 0.20,
                maxGlareRegionFraction = 0.12,
                minRectangularity = 0.80,
            ),
            orb = OrbExtraction(
                nfeatures = 1500,
                scaleFactor = 1.2,
                nlevels = 8,
                edgeThreshold = 31,
                firstLevel = 0,
                wtaK = 2,
                scoreTypeHarris = true,
                patchSize = 31,
                fastThreshold = 20,
            ),
            match = MatchThresholds(
                inlierReprojectionTolerancePx = 5.0,
                countScoreInliersDivisor = 40.0,
                ratioScoreOffset = 0.20,
                ratioScoreSpan = 0.60,
                coverageScoreTarget = 0.45,
                reprojectionScoreMaxMedianErrorPx = 8.0,
                weakMinRatioMatches = 10,
                weakMinInliers = 6,
                weakMinInlierRatio = 0.25,
                weakMinCoverage = 0.10,
                weakMinGridCells = 3,
                coverageGridSize = 4,
                strongMinRatioMatches = 20,
                strongMinInliers = 15,
                strongMinInlierRatio = 0.35,
                strongMinCoverage = 0.20,
                strongMaxMedianErrorPx = 4.0,
                homographyAreaRatioMin = 0.20,
                homographyAreaRatioMax = 5.0,
                homographyMaxOppositeEdgeRatio = 4.0,
            ),
            ranking = RankingThresholds(
                duplicateFrontMargin = 0.08,
                autoFrontMin = 0.70,
                autoMarginOverRunnerUp = 0.12,
                duplicateFrontMinScore = 0.65,
                chooserFrontMin = 0.40,
                confidenceAreaWeight = 0.40,
                confidenceRectangularityWeight = 0.30,
                confidenceEdgeSupportWeight = 0.20,
                confidenceGuideProximityWeight = 0.10,
                minContourConfidence = 0.70,
            ),
        )

        /**
         * Strict parse: unknown JSON keys, missing fields, wrong profile id,
         * unsupported version, or out-of-range thresholds all fail closed.
         */
        fun fromJson(text: String): RecognitionProfile {
            val dto = strictJson.decodeFromString<ProfileDto>(text)
            val restored = dto.toDomain()
            require(restored.formatVersion == FORMAT_VERSION_1) { "unsupported profile format version" }
            require(restored.profileId == MVP_ORB_V1_ID) { "unknown profile id" }
            restored.validate()
            return restored
        }
    }
}

@Serializable
internal data class ProfileDto(
    val formatVersion: Int,
    val profileId: String,
    val capture: CaptureDto,
    val orb: OrbDto,
    val match: MatchDto,
    val ranking: RankingDto,
) {
    fun toDomain() = RecognitionProfile(
        formatVersion = formatVersion,
        profileId = profileId,
        capture = RecognitionProfile.CaptureGates(
            capture.minCardAreaRatio,
            capture.minShortEdgeAfterWarpPx,
            capture.canonicalLongEdgePx,
            capture.maxCornerOutsideFramePx,
            capture.aspectRatioMin,
            capture.aspectRatioMax,
            capture.minLaplacianVariance,
            capture.maxNearBlackFraction,
            capture.maxClippedWhiteFraction,
            capture.maxGlareRegionFraction,
            capture.minRectangularity,
        ),
        orb = RecognitionProfile.OrbExtraction(
            orb.nfeatures,
            orb.scaleFactor,
            orb.nlevels,
            orb.edgeThreshold,
            orb.firstLevel,
            orb.wtaK,
            orb.scoreTypeHarris,
            orb.patchSize,
            orb.fastThreshold,
        ),
        match = RecognitionProfile.MatchThresholds(
            match.inlierReprojectionTolerancePx,
            match.countScoreInliersDivisor,
            match.ratioScoreOffset,
            match.ratioScoreSpan,
            match.coverageScoreTarget,
            match.reprojectionScoreMaxMedianErrorPx,
            match.weakMinRatioMatches,
            match.weakMinInliers,
            match.weakMinInlierRatio,
            match.weakMinCoverage,
            match.weakMinGridCells,
            match.coverageGridSize,
            match.strongMinRatioMatches,
            match.strongMinInliers,
            match.strongMinInlierRatio,
            match.strongMinCoverage,
            match.strongMaxMedianErrorPx,
            match.homographyAreaRatioMin,
            match.homographyAreaRatioMax,
            match.homographyMaxOppositeEdgeRatio,
        ),
        ranking = RecognitionProfile.RankingThresholds(
            ranking.duplicateFrontMargin,
            ranking.autoFrontMin,
            ranking.autoMarginOverRunnerUp,
            ranking.duplicateFrontMinScore,
            ranking.chooserFrontMin,
            ranking.confidenceAreaWeight,
            ranking.confidenceRectangularityWeight,
            ranking.confidenceEdgeSupportWeight,
            ranking.confidenceGuideProximityWeight,
            ranking.minContourConfidence,
        ),
    )

    @Serializable
    internal data class CaptureDto(
        val minCardAreaRatio: Double,
        val minShortEdgeAfterWarpPx: Int,
        val canonicalLongEdgePx: Int,
        val maxCornerOutsideFramePx: Int,
        val aspectRatioMin: Double,
        val aspectRatioMax: Double,
        val minLaplacianVariance: Double,
        val maxNearBlackFraction: Double,
        val maxClippedWhiteFraction: Double,
        val maxGlareRegionFraction: Double,
        val minRectangularity: Double = 0.80,
    )

    @Serializable
    internal data class OrbDto(
        val nfeatures: Int,
        val scaleFactor: Double,
        val nlevels: Int,
        val edgeThreshold: Int,
        val firstLevel: Int,
        val wtaK: Int,
        val scoreTypeHarris: Boolean,
        val patchSize: Int,
        val fastThreshold: Int,
    )

    @Serializable
    internal data class MatchDto(
        val inlierReprojectionTolerancePx: Double,
        val countScoreInliersDivisor: Double,
        val ratioScoreOffset: Double,
        val ratioScoreSpan: Double,
        val coverageScoreTarget: Double,
        val reprojectionScoreMaxMedianErrorPx: Double,
        val weakMinRatioMatches: Int,
        val weakMinInliers: Int,
        val weakMinInlierRatio: Double,
        val weakMinCoverage: Double,
        val weakMinGridCells: Int,
        val coverageGridSize: Int,
        val strongMinRatioMatches: Int,
        val strongMinInliers: Int,
        val strongMinInlierRatio: Double,
        val strongMinCoverage: Double,
        val strongMaxMedianErrorPx: Double,
        val homographyAreaRatioMin: Double,
        val homographyAreaRatioMax: Double,
        val homographyMaxOppositeEdgeRatio: Double,
    )

    @Serializable
    internal data class RankingDto(
        val duplicateFrontMargin: Double,
        val autoFrontMin: Double,
        val autoMarginOverRunnerUp: Double,
        val duplicateFrontMinScore: Double,
        val chooserFrontMin: Double,
        val confidenceAreaWeight: Double = 0.40,
        val confidenceRectangularityWeight: Double = 0.30,
        val confidenceEdgeSupportWeight: Double = 0.20,
        val confidenceGuideProximityWeight: Double = 0.10,
        val minContourConfidence: Double,
    )
}

private fun RecognitionProfile.validate() {
    with(capture) {
        require(minCardAreaRatio in 0.0..1.0)
        require(minShortEdgeAfterWarpPx > 0)
        require(canonicalLongEdgePx > minShortEdgeAfterWarpPx)
        require(maxCornerOutsideFramePx >= 0)
        require(aspectRatioMin < aspectRatioMax && aspectRatioMin > 0.0)
        require(minLaplacianVariance > 0.0)
        require(maxNearBlackFraction in 0.0..1.0)
        require(maxClippedWhiteFraction in 0.0..1.0)
        require(maxGlareRegionFraction in 0.0..1.0)
        require(minRectangularity in 0.0..1.0)
    }
    with(orb) {
        require(nfeatures > 0 && nlevels > 0 && edgeThreshold > 0 && patchSize > 0)
        require(scaleFactor > 1.0)
        require(firstLevel >= 0)
        require(wtaK in 2..4)
        require(fastThreshold >= 0)
    }
    with(match) {
        require(inlierReprojectionTolerancePx > 0.0)
        require(countScoreInliersDivisor > 0.0)
        require(ratioScoreOffset < ratioScoreSpan && ratioScoreSpan > 0.0)
        require(coverageScoreTarget in 0.0..1.0)
        require(reprojectionScoreMaxMedianErrorPx > 0.0)
        require(weakMinRatioMatches <= strongMinRatioMatches)
        require(weakMinInliers <= strongMinInliers)
        require(weakMinInlierRatio <= strongMinInlierRatio)
        require(weakMinCoverage <= strongMinCoverage)
        require(weakMinGridCells >= 2 && coverageGridSize >= 2)
        require(strongMaxMedianErrorPx <= reprojectionScoreMaxMedianErrorPx)
        require(homographyAreaRatioMin > 0.0 && homographyAreaRatioMin < homographyAreaRatioMax)
        require(homographyMaxOppositeEdgeRatio > 1.0)
    }
    with(ranking) {
        require(duplicateFrontMargin in 0.0..1.0)
        require(autoFrontMin in 0.0..1.0)
        require(autoMarginOverRunnerUp in 0.0..1.0)
        require(duplicateFrontMinScore in 0.0..1.0)
        require(chooserFrontMin in 0.0..autoFrontMin) { "chooser floor must not exceed automatic floor" }
        require(minContourConfidence in 0.0..1.0)
        // Confidence weights must form a convex combination (epsilon-tolerant).
        val weightSum = confidenceAreaWeight + confidenceRectangularityWeight +
            confidenceEdgeSupportWeight + confidenceGuideProximityWeight
        require(kotlin.math.abs(weightSum - 1.0) < 1e-9) { "confidence weights must sum to 1" }
        require(listOf(confidenceAreaWeight, confidenceRectangularityWeight, confidenceEdgeSupportWeight, confidenceGuideProximityWeight).all { it >= 0.0 })
    }
}

internal fun RecognitionProfile.toDto() = ProfileDto(
    formatVersion = formatVersion,
    profileId = profileId,
    capture = ProfileDto.CaptureDto(
        capture.minCardAreaRatio,
        capture.minShortEdgeAfterWarpPx,
        capture.canonicalLongEdgePx,
        capture.maxCornerOutsideFramePx,
        capture.aspectRatioMin,
        capture.aspectRatioMax,
        capture.minLaplacianVariance,
        capture.maxNearBlackFraction,
        capture.maxClippedWhiteFraction,
        capture.maxGlareRegionFraction,
        capture.minRectangularity,
    ),
    orb = ProfileDto.OrbDto(
        orb.nfeatures,
        orb.scaleFactor,
        orb.nlevels,
        orb.edgeThreshold,
        orb.firstLevel,
        orb.wtaK,
        orb.scoreTypeHarris,
        orb.patchSize,
        orb.fastThreshold,
    ),
    match = ProfileDto.MatchDto(
        match.inlierReprojectionTolerancePx,
        match.countScoreInliersDivisor,
        match.ratioScoreOffset,
        match.ratioScoreSpan,
        match.coverageScoreTarget,
        match.reprojectionScoreMaxMedianErrorPx,
        match.weakMinRatioMatches,
        match.weakMinInliers,
        match.weakMinInlierRatio,
        match.weakMinCoverage,
        match.weakMinGridCells,
        match.coverageGridSize,
        match.strongMinRatioMatches,
        match.strongMinInliers,
        match.strongMinInlierRatio,
        match.strongMinCoverage,
        match.strongMaxMedianErrorPx,
        match.homographyAreaRatioMin,
        match.homographyAreaRatioMax,
        match.homographyMaxOppositeEdgeRatio,
    ),
    ranking = ProfileDto.RankingDto(
        ranking.duplicateFrontMargin,
        ranking.autoFrontMin,
        ranking.autoMarginOverRunnerUp,
        ranking.duplicateFrontMinScore,
        ranking.chooserFrontMin,
        ranking.confidenceAreaWeight,
        ranking.confidenceRectangularityWeight,
        ranking.confidenceEdgeSupportWeight,
        ranking.confidenceGuideProximityWeight,
        ranking.minContourConfidence,
    ),
)

object RecognitionProfileJson {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(profile: RecognitionProfile): String =
        json.encodeToString(ProfileDto.serializer(), profile.toDto())
}
