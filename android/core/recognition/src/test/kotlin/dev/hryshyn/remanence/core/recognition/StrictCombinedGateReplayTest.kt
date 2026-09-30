package dev.hryshyn.remanence.core.recognition

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assumptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exact frozen replay for the S4 `strict_combined` gate (T074).
 *
 * Replays both frozen traces —
 * `evidence/frame_first_v2/results/per_image.json` (baseline matcher) and
 * `evidence/frame_first_safety_v2/results/per_image.json` (frozen policy
 * outcomes) — through **this** gate and proves the frozen aggregate
 * `219 correct / 185 known reject / 2 wrong / 91 unknown reject / 0 false
 * accept` with zero baseline-reject-to-accept promotions.
 *
 * The frozen archive is task evidence, not a committed fixture: the directory
 * comes from the `T074_FREEZE_DIR` environment variable, so no dataset path and
 * no ground-truth label is present in production or test sources. When the
 * variable is absent the replay is skipped, never silently passed; when it is
 * set but the trace is missing, the test fails loudly.
 */
class StrictCombinedGateReplayTest {

    private data class Record(
        val path: String,
        val known: Boolean,
        val baselineMatched: Boolean,
        val baselineOutcome: String,
        val frozenOutcome: String,
    )

    private data class Evidence(
        val baselineMatched: Boolean,
        val geometryVerified: Boolean,
        val finalScore: Double,
        val inlierRatio: Double,
        val coverage: Double,
        val components: Int,
    ) {
        fun toGateEvidence() = StrictCombinedEvidence(
            geometryVerified = geometryVerified,
            finalScore = finalScore,
            inlierRatio = inlierRatio,
            coverage = coverage,
            components = components,
        )
    }

    @Test
    fun frozenReplayReproducesTheFrozenAggregate() {
        val root = System.getenv(FREEZE_DIR_ENV)
        Assumptions.assumeTrue(
            !root.isNullOrBlank(),
            "$FREEZE_DIR_ENV not set — frozen replay is evidence-gated",
        )
        val baselineFile = File(root, BASELINE_TRACE)
        val safetyFile = File(root, SAFETY_TRACE)
        assertTrue(baselineFile.isFile, "missing baseline trace: $baselineFile")
        assertTrue(safetyFile.isFile, "missing safety trace: $safetyFile")

        val baseline = Json.parseToJsonElement(baselineFile.readText()).jsonObject
        val safety = Json.parseToJsonElement(safetyFile.readText()).jsonObject
        val rows = baseline.getValue("rows").jsonArray
        val records = safety.getValue("records").jsonArray

        val evidenceByPath = HashMap<String, Evidence>(rows.size)
        rows.forEach { element ->
            val row = element.jsonObject
            val path = row.str("path") ?: error("baseline row without path")
            evidenceByPath[path] = evidenceOf(row)
        }
        assertEquals(497, evidenceByPath.size, "baseline trace row count")
        assertEquals(497, records.size, "safety trace record count")

        var correct = 0
        var knownReject = 0
        var wrong = 0
        var unknownReject = 0
        var falseAccept = 0
        var promotions = 0
        var disagreements = 0

        records.forEach { element ->
            val record = recordOf(element)
            val evidence = evidenceByPath[record.path]
                ?: error("no baseline row for ${record.path}")

            // The frozen trace and the baseline trace must agree on the
            // baseline verdict, otherwise the join is meaningless.
            assertEquals(record.baselineMatched, evidence.baselineMatched, record.path)

            val decision = StrictCombinedGate.decide(
                record.baselineMatched,
                evidence.toGateEvidence(),
            )
            if (!record.baselineMatched && decision is StrictCombinedDecision.Keep) {
                promotions++
            }
            val outcome = when {
                !record.baselineMatched -> record.baselineOutcome
                decision is StrictCombinedDecision.Keep -> record.baselineOutcome
                else -> OUTCOME_REJECT
            }
            if (outcome != record.frozenOutcome) {
                disagreements++
            }

            if (record.known) {
                when (outcome) {
                    OUTCOME_CORRECT -> correct++
                    OUTCOME_WRONG -> wrong++
                    OUTCOME_REJECT -> knownReject++
                    else -> error("unexpected known outcome '$outcome' for ${record.path}")
                }
            } else {
                when (outcome) {
                    OUTCOME_REJECT -> unknownReject++
                    OUTCOME_FALSE_ACCEPT -> falseAccept++
                    else -> error("unexpected unknown outcome '$outcome' for ${record.path}")
                }
            }
        }

        // Frozen aggregate: 219 / 185 / 2 / 91 / 0 on 497 queries.
        assertEquals(497, correct + knownReject + wrong + unknownReject + falseAccept)
        assertEquals(219, correct, "correct")
        assertEquals(185, knownReject, "known reject")
        assertEquals(2, wrong, "wrong")
        assertEquals(91, unknownReject, "unknown reject")
        assertEquals(0, falseAccept, "false accept")
        // Reject-only contract, and exact agreement with the frozen policy run.
        assertEquals(0, promotions, "baseline reject promoted to accept")
        assertEquals(0, disagreements, "disagreements with frozen strict_combined")
    }

    private fun recordOf(element: kotlinx.serialization.json.JsonElement): Record {
        val record = element.jsonObject
        val policies = record["policies"]?.jsonObject ?: error("record without policies")
        val strict = policies["strict_combined"]?.jsonObject
            ?: error("record without strict_combined policy")
        return Record(
            path = record.str("path") ?: error("safety record without path"),
            known = record.str("known") == "true",
            baselineMatched = record.str("baseline_status") == STATUS_MATCHED,
            baselineOutcome = record.str("baseline_outcome")
                ?: error("record without baseline_outcome"),
            frozenOutcome = strict.str("outcome") ?: error("strict_combined without outcome"),
        )
    }

    /** Mirrors `frame_first_safety_v2/common.py::evidence` field mapping. */
    private fun evidenceOf(row: JsonObject): Evidence {
        val best = row["best"]?.jsonObject
        val quality = row["localization"]?.jsonObject?.get("quality")?.jsonObject
        return Evidence(
            baselineMatched = row.str("status") == STATUS_MATCHED,
            geometryVerified = best?.str("geometry_reason") == GEOMETRY_VERIFIED,
            finalScore = row.num("final_score") ?: 0.0,
            inlierRatio = best?.num("inlier_ratio") ?: 0.0,
            coverage = best?.num("coverage") ?: 0.0,
            components = quality?.intOrNull("components") ?: 0,
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.num(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    private companion object {
        const val FREEZE_DIR_ENV = "T074_FREEZE_DIR"
        const val BASELINE_TRACE = "evidence/frame_first_v2/results/per_image.json"
        const val SAFETY_TRACE = "evidence/frame_first_safety_v2/results/per_image.json"
        const val STATUS_MATCHED = "matched"
        const val GEOMETRY_VERIFIED = "verified"
        const val OUTCOME_CORRECT = "correct"
        const val OUTCOME_WRONG = "wrong"
        const val OUTCOME_REJECT = "reject"
        const val OUTCOME_FALSE_ACCEPT = "false_accept"
    }
}
