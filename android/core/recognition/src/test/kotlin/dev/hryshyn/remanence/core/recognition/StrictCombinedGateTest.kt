package dev.hryshyn.remanence.core.recognition

import kotlin.math.nextDown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Boundary, order, monotonicity and identity-freeness tests for the frozen
 * `strict_combined` gate (T074 / S4).
 *
 * Every number here is the frozen preset value; nothing in this file invents a
 * threshold. The dataset replay lives in [StrictCombinedGateReplayTest] and
 * reads the frozen trace at run time, so no dataset path or ground-truth label
 * is committed to production or test sources.
 */
class StrictCombinedGateTest {

    private fun passing() = StrictCombinedEvidence(
        geometryVerified = true,
        finalScore = 40.0,
        inlierRatio = 0.95,
        coverage = 0.40,
        components = 1,
    )

    private fun decide(
        baselineAccepted: Boolean = true,
        evidence: StrictCombinedEvidence? = passing(),
    ): StrictCombinedDecision = StrictCombinedGate.decide(baselineAccepted, evidence)

    private fun reasonOf(
        baselineAccepted: Boolean = true,
        evidence: StrictCombinedEvidence? = passing(),
    ): StrictCombinedRejectReason =
        assertIs<StrictCombinedDecision.Reject>(decide(baselineAccepted, evidence)).reason

    // -- frozen preset surface ------------------------------------------------

    @Test
    fun frozenPresetConstantsMatchTheArchive() {
        assertEquals("strict_combined", StrictCombinedGate.NAME)
        assertEquals(20.0, StrictCombinedGate.MIN_SCORE)
        assertEquals(0.85, StrictCombinedGate.MIN_INLIER_RATIO)
        assertEquals(0.20, StrictCombinedGate.MIN_COVERAGE)
        assertEquals(1, StrictCombinedGate.MAX_COMPONENTS)
        assertEquals(0.50, StrictCombinedGate.MULTI_MIN_COVERAGE)
        assertEquals(0.90, StrictCombinedGate.MULTI_MIN_INLIER_RATIO)
    }

    @Test
    fun versionPinIsStableAndNamespaced() {
        assertTrue(StrictCombinedGate.VERSION.startsWith("${StrictCombinedGate.NAME}/"))
        assertEquals("strict_combined/1", StrictCombinedGate.VERSION)
    }

    @Test
    fun rejectReasonCodesMatchTheFrozenPolicyStrings() {
        assertEquals(
            listOf(
                "geometry_not_verified",
                "final_score_below_floor",
                "inlier_ratio_below_floor",
                "coverage_below_floor",
                "multiple_components_not_admitted",
            ),
            listOf(
                StrictCombinedRejectReason.GEOMETRY_NOT_VERIFIED.code,
                StrictCombinedRejectReason.FINAL_SCORE_BELOW_FLOOR.code,
                StrictCombinedRejectReason.INLIER_RATIO_BELOW_FLOOR.code,
                StrictCombinedRejectReason.COVERAGE_BELOW_FLOOR.code,
                StrictCombinedRejectReason.MULTIPLE_COMPONENTS_NOT_ADMITTED.code,
            ),
        )
    }

    // -- boundaries (each floor flips exactly at its frozen value) ------------

    @Test
    fun evidenceExactlyOnEveryFloorKeeps() {
        assertTrue(decide(evidence = passing().copy(finalScore = 20.0, inlierRatio = 0.85, coverage = 0.20, components = 1)) is StrictCombinedDecision.Keep)
    }

    @Test
    fun scoreNineteenRejects() {
        assertEquals(
            StrictCombinedRejectReason.FINAL_SCORE_BELOW_FLOOR,
            reasonOf(evidence = passing().copy(finalScore = 19.0)),
        )
        // 20 keeps, 19 rejects: the flip is exactly at the floor.
        assertTrue(decide(evidence = passing().copy(finalScore = 20.0)) is StrictCombinedDecision.Keep)
    }

    @Test
    fun inlierRatioFloorFlipsAtEightyFivePercent() {
        assertEquals(
            StrictCombinedRejectReason.INLIER_RATIO_BELOW_FLOOR,
            reasonOf(evidence = passing().copy(inlierRatio = 0.85.nextDown())),
        )
        assertTrue(decide(evidence = passing().copy(inlierRatio = 0.85)) is StrictCombinedDecision.Keep)
    }

    @Test
    fun coverageFloorFlipsAtTwentyPercent() {
        assertEquals(
            StrictCombinedRejectReason.COVERAGE_BELOW_FLOOR,
            reasonOf(evidence = passing().copy(coverage = 0.20.nextDown())),
        )
        assertTrue(decide(evidence = passing().copy(coverage = 0.20)) is StrictCombinedDecision.Keep)
    }

    @Test
    fun twoComponentsAtCoverageFortyRejects() {
        assertEquals(
            StrictCombinedRejectReason.MULTIPLE_COMPONENTS_NOT_ADMITTED,
            reasonOf(evidence = passing().copy(components = 2, coverage = 0.40)),
        )
    }

    @Test
    fun twoComponentsAtFiftyCoverageAndNinetyRatioMayPass() {
        assertTrue(
            decide(
                evidence = passing().copy(components = 2, coverage = 0.50, inlierRatio = 0.90),
            ) is StrictCombinedDecision.Keep,
        )
        // Just below either multi-component condition still rejects.
        assertEquals(
            StrictCombinedRejectReason.MULTIPLE_COMPONENTS_NOT_ADMITTED,
            reasonOf(evidence = passing().copy(components = 2, coverage = 0.4999, inlierRatio = 0.90)),
        )
        assertEquals(
            StrictCombinedRejectReason.MULTIPLE_COMPONENTS_NOT_ADMITTED,
            reasonOf(evidence = passing().copy(components = 2, coverage = 0.50, inlierRatio = 0.8999)),
        )
    }

    @Test
    fun multiComponentAdmissionStillRequiresTheSingleComponentFloors() {
        // Multi admission cannot rescue evidence that already failed a floor:
        // coverage below 0.20 stops at the coverage floor, not at the
        // component check, because the floors are evaluated first.
        assertEquals(
            StrictCombinedRejectReason.COVERAGE_BELOW_FLOOR,
            reasonOf(evidence = passing().copy(components = 2, coverage = 0.10, inlierRatio = 0.95)),
        )
        assertEquals(
            StrictCombinedRejectReason.INLIER_RATIO_BELOW_FLOOR,
            reasonOf(evidence = passing().copy(components = 2, coverage = 0.60, inlierRatio = 0.50)),
        )
    }

    // -- frozen check order ---------------------------------------------------

    @Test
    fun geometryIsCheckedBeforeEveryOtherConstraint() {
        assertEquals(
            StrictCombinedRejectReason.GEOMETRY_NOT_VERIFIED,
            reasonOf(
                evidence = passing().copy(
                    geometryVerified = false,
                    finalScore = 0.0,
                    inlierRatio = 0.0,
                    coverage = 0.0,
                    components = 9,
                ),
            ),
        )
    }

    @Test
    fun scoreIsCheckedBeforeInlierRatioAndCoverage() {
        assertEquals(
            StrictCombinedRejectReason.FINAL_SCORE_BELOW_FLOOR,
            reasonOf(evidence = passing().copy(finalScore = 1.0, inlierRatio = 0.0, coverage = 0.0)),
        )
    }

    @Test
    fun inlierRatioIsCheckedBeforeCoverage() {
        assertEquals(
            StrictCombinedRejectReason.INLIER_RATIO_BELOW_FLOOR,
            reasonOf(evidence = passing().copy(inlierRatio = 0.10, coverage = 0.0)),
        )
    }

    @Test
    fun coverageIsCheckedBeforeTheComponentCeiling() {
        assertEquals(
            StrictCombinedRejectReason.COVERAGE_BELOW_FLOOR,
            reasonOf(evidence = passing().copy(coverage = 0.10, components = 5)),
        )
    }

    // -- reject-only / baseline contract --------------------------------------

    @Test
    fun baselineRejectStaysRejectEvenWithPerfectEvidence() {
        assertEquals(
            StrictCombinedRejectReason.BASELINE_NOT_ACCEPTED,
            reasonOf(baselineAccepted = false, evidence = passing()),
        )
        // A baseline reject with null evidence must still not be promoted.
        assertEquals(
            StrictCombinedRejectReason.BASELINE_NOT_ACCEPTED,
            reasonOf(baselineAccepted = false, evidence = null),
        )
    }

    @Test
    fun baselineAcceptWithPerfectEvidenceIsKept() {
        assertTrue(decide(baselineAccepted = true, evidence = passing()) is StrictCombinedDecision.Keep)
    }

    // -- monotonicity: worsening evidence can only move keep -> reject --------

    @Test
    fun everySingleWorseningMovesKeepToReject() {
        assertTrue(decide() is StrictCombinedDecision.Keep)
        val worsenings = listOf(
            passing().copy(geometryVerified = false),
            passing().copy(finalScore = 19.0),
            passing().copy(inlierRatio = 0.84),
            passing().copy(coverage = 0.19),
            passing().copy(components = 2),
        )
        for (worse in worsenings) {
            assertTrue(
                decide(evidence = worse) is StrictCombinedDecision.Reject,
                "expected reject for $worse",
            )
        }
    }

    @Test
    fun furtherWorseningNeverTurnsARejectIntoAKeep() {
        val alreadyRejected = listOf(
            passing().copy(finalScore = 19.0),
            passing().copy(components = 2, coverage = 0.40),
            passing().copy(inlierRatio = 0.10),
            passing().copy(coverage = 0.10),
            passing().copy(geometryVerified = false),
        )
        for (base in alreadyRejected) {
            val further = base.copy(
                finalScore = 0.0,
                inlierRatio = 0.0,
                coverage = 0.0,
                components = 99,
            )
            assertIs<StrictCombinedDecision.Reject>(decide(evidence = base))
            assertIs<StrictCombinedDecision.Reject>(
                decide(evidence = further),
                "reject must survive further worsening: $base",
            )
        }
    }

    @Test
    fun reportedReasonIsTheFirstFailingConstraintInFrozenOrder() {
        // Worsening an *earlier* constraint legitimately changes which reason
        // is reported: the reason names the first constraint in frozen order
        // that the evidence violates, not the most severely failed one.
        assertEquals(
            StrictCombinedRejectReason.MULTIPLE_COMPONENTS_NOT_ADMITTED,
            reasonOf(evidence = passing().copy(components = 2, coverage = 0.40)),
        )
        assertEquals(
            StrictCombinedRejectReason.COVERAGE_BELOW_FLOOR,
            reasonOf(evidence = passing().copy(components = 2, coverage = 0.10)),
        )
        assertEquals(
            StrictCombinedRejectReason.GEOMETRY_NOT_VERIFIED,
            reasonOf(
                evidence = passing().copy(
                    geometryVerified = false,
                    finalScore = 0.0,
                    inlierRatio = 0.0,
                    coverage = 0.0,
                    components = 99,
                ),
            ),
        )
        assertEquals(
            StrictCombinedRejectReason.FINAL_SCORE_BELOW_FLOOR,
            reasonOf(
                evidence = passing().copy(finalScore = 0.0, inlierRatio = 0.0, coverage = 0.0),
            ),
        )
    }

    // -- fail closed on invalid / non-finite / incomplete evidence ------------

    @Test
    fun missingEvidenceFailsClosed() {
        assertEquals(StrictCombinedRejectReason.INVALID_EVIDENCE, reasonOf(evidence = null))
    }

    @Test
    fun nonFiniteEvidenceFailsClosed() {
        for (broken in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(
                StrictCombinedRejectReason.INVALID_EVIDENCE,
                reasonOf(evidence = passing().copy(finalScore = broken)),
                "finalScore=$broken",
            )
            assertEquals(
                StrictCombinedRejectReason.INVALID_EVIDENCE,
                reasonOf(evidence = passing().copy(coverage = broken)),
                "coverage=$broken",
            )
            assertEquals(
                StrictCombinedRejectReason.INVALID_EVIDENCE,
                reasonOf(evidence = passing().copy(inlierRatio = broken)),
                "inlierRatio=$broken",
            )
        }
    }

    @Test
    fun negativeComponentCountFailsClosed() {
        assertEquals(
            StrictCombinedRejectReason.INVALID_EVIDENCE,
            reasonOf(evidence = passing().copy(components = -1)),
        )
    }

    // -- identity-free decision surface --------------------------------------

    @Test
    fun evidenceSurfaceExposesOnlyImageDerivedFields() {
        val fields = StrictCombinedEvidence::class.java.declaredFields.map { it.name }.toSet()
        assertEquals(
            setOf("geometryVerified", "finalScore", "inlierRatio", "coverage", "components"),
            fields,
        )
    }

    @Test
    fun decisionSurfaceCannotCarryIdentityScenarioOrEnvironment() {
        val fields = StrictCombinedEvidence::class.java.declaredFields.map { it.name }
        for (banned in listOf(
            "cardId", "card_id", "expected", "expectedId", "path", "filename",
            "scenario", "scenarioId", "batch", "batchId", "environment",
            "identity", "profile", "dataset", "groundTruth", "label",
        )) {
            assertTrue(fields.none { it.equals(banned, ignoreCase = true) }, "found banned field $banned")
        }
        // The decision takes exactly two inputs: the baseline verdict and the
        // image-derived evidence. Nothing else can be threaded through.
        val decide = StrictCombinedGate::class.java.getMethod(
            "decide",
            Boolean::class.javaPrimitiveType,
            StrictCombinedEvidence::class.java,
        )
        assertEquals(2, decide.parameterCount)
        assertEquals(StrictCombinedDecision::class.java, decide.returnType)
    }
}
