package dev.hryshyn.remanence.ui.capsule

import dev.hryshyn.remanence.core.model.GeneratorEditorialRows
import dev.hryshyn.remanence.core.model.GeneratorExpression

/**
 * T186 M14d-scan: shared validated v2 expression fixture (exact logic
 * of the route-state builder — a genuinely planned expression, never
 * hand-assembled). Both the reader-mapping test and the real v2 open
 * test consume this one source so the admitted expression is always
 * the production-shaped artifact.
 */
internal val testExpressionFitsPort = GeneratorEditorialRows.NoteMeasurementPort {
    GeneratorEditorialRows.NoteMeasurement.Fits(GeneratorEditorialRows.NOTE_REGION)
}

internal fun testV2Expression(note: String? = null): GeneratorExpression.ResolvedExpression {
    val input = GeneratorExpression.GeneratorInput(
        ownerId = "owner-1",
        epoch = 1L,
        photos = listOf("p1", "p2", "p3").mapIndexed { index, id ->
            GeneratorExpression.PhotoRef(
                contentId = id,
                ordinal = index,
                widthPx = 1000,
                heightPx = 1000,
                contentHash = ('a' + index).toString().repeat(64),
            )
        },
        note = note,
        music = null,
    )
    val plan = if (note.isNullOrEmpty()) {
        GeneratorEditorialRows.plan(input)
    } else {
        GeneratorEditorialRows.plan(input, testExpressionFitsPort)
    }
    check(plan is GeneratorEditorialRows.PlanResult.Planned) { "expected Planned, got $plan" }
    return (plan as GeneratorEditorialRows.PlanResult.Planned).expression
}
