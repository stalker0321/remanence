package dev.hryshyn.remanence.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** ADR-018 BEXPR01 projection hash tests (host-side, model only). */
class GeneratorExpressionProjectionTest {

    private fun photo(id: String, ordinal: Int, hash: String) =
        GeneratorExpression.PhotoRef(id, ordinal, 1000, 1000, hash)

    private fun input(owner: String = "owner-1", epoch: Long = 7L) = GeneratorExpression.GeneratorInput(
        ownerId = owner,
        epoch = epoch,
        photos = listOf(
            photo("p1", 0, "a".repeat(64)),
            photo("p2", 1, "b".repeat(64)),
            photo("p3", 2, "c".repeat(64)),
        ),
        note = null,
        music = null,
    )

    private fun expression(input: GeneratorExpression.GeneratorInput): GeneratorExpression.ResolvedExpression {
        val result = GeneratorEditorialRows.plan(input)
        assertTrue(result is GeneratorEditorialRows.PlanResult.Planned, "expected Planned, got $result")
        return (result as GeneratorEditorialRows.PlanResult.Planned).expression
    }

    private fun blobs(expression: GeneratorExpression.ResolvedExpression): Map<String, ByteArray> =
        expression.input.photos.associate { it.contentId to ByteArray(16) { 1 } }

    @Test
    fun projectionIsCanonicalHexAndDeterministic() {
        val expression = expression(input())
        val first = GeneratorExpressionProjection.hash(expression, "cand-1", blobs(expression))
        val second = GeneratorExpressionProjection.hash(expression, "cand-1", blobs(expression))
        assertEquals(first, second)
        assertTrue(first.matches(Regex("[0-9a-f]{64}")), first)
    }

    @Test
    fun projectionExcludesOwnerAndEpoch() {
        val a = expression(input(owner = "owner-1", epoch = 7L))
        val b = expression(input(owner = "owner-2", epoch = 99L))
        assertEquals(
            GeneratorExpressionProjection.hash(a, "cand-1", blobs(a)),
            GeneratorExpressionProjection.hash(b, "cand-1", blobs(b)),
            "projection must not expose sender owner/epoch",
        )
    }

    @Test
    fun projectionBindsCandidateGeometryAndSourceBlobs() {
        val expression = expression(input())
        val base = GeneratorExpressionProjection.hash(expression, "cand-1", blobs(expression))
        assertNotEquals(
            base,
            GeneratorExpressionProjection.hash(expression, "cand-2", blobs(expression)),
        )
        val moved = expression.copy(
            placements = expression.placements.mapIndexed { index, p ->
                if (index == 0) p.copy(x = p.x + 1) else p
            },
        )
        assertNotEquals(base, GeneratorExpressionProjection.hash(moved, "cand-1", blobs(expression)))
        val changedBlob = blobs(expression).toMutableMap().apply { put("p1", ByteArray(16) { 2 }) }
        assertNotEquals(base, GeneratorExpressionProjection.hash(expression, "cand-1", changedBlob))
    }

    @Test
    fun projectionFailsClosedOnMissingOrWrongSizedBlob() {
        val expression = expression(input())
        val missing = blobs(expression).toMutableMap().apply { remove("p2") }
        assertFailsWith<IllegalArgumentException> {
            GeneratorExpressionProjection.hash(expression, "cand-1", missing)
        }
    }
}
