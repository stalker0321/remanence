package dev.hryshyn.remanence.ui.create

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import dev.hryshyn.remanence.core.model.GeneratorEditorialRows
import dev.hryshyn.remanence.core.model.GeneratorExpression

/**
 * Shared BER1 note-measurement reporter (moved verbatim out of
 * `GeneratorBer1PreviewHost.NotePendingPreview` for the T002 chooser
 * cutover). It measures a non-empty note on-device with the bundled canvas
 * measurer, plans the BER1 expression, and reports the real fitted plan —
 * never during composition, only after a successful measured plan exists.
 * It renders nothing itself; callers render from the returned plan.
 *
 * This keeps BER1 note publication byte-identical after the content step
 * mounts the collage chooser instead of the BER1 preview host: the collage
 * host reports through this for `NotePending` inputs while the BER1 host
 * keeps rendering through it. C3 owns deleting the BER1 sender path.
 */
@Composable
internal fun rememberBer1NotePlan(
    input: GeneratorExpression.GeneratorInput,
): GeneratorEditorialRows.PlanResult {
    val measurer = rememberCanvasNoteMeasurer()
    val style = rememberCanvasNoteStyle()
    val region = GeneratorEditorialRows.NOTE_REGION
    val measurement = remember(measurer, style) {
        GeneratorEditorialRows.NoteMeasurementPort { note ->
            val layout = measurer.measure(
                text = AnnotatedString(note),
                style = style,
                overflow = TextOverflow.Clip,
                softWrap = true,
                maxLines = Int.MAX_VALUE,
                constraints = Constraints(maxWidth = region.width, maxHeight = region.height),
            )
            val fits = !layout.hasVisualOverflow &&
                layout.size.width in 1..region.width &&
                layout.size.height in 1..region.height
            if (fits) {
                GeneratorEditorialRows.NoteMeasurement.Fits(region)
            } else {
                GeneratorEditorialRows.NoteMeasurement.DoesNotFit("note overflows the preview region")
            }
        }
    }
    return remember(input, measurement) {
        GeneratorEditorialRows.plan(input, measurement)
    }
}

/**
 * Reports the real measured fit to [onMeasured] once a planned expression
 * exists. The VM freshness-checks the reported input before freezing it for
 * publish.
 */
@Composable
internal fun Ber1NoteMeasureReporter(
    input: GeneratorExpression.GeneratorInput,
    onMeasured: (GeneratorExpression.ResolvedExpression) -> Unit,
) {
    val plan = rememberBer1NotePlan(input)
    // Report the real measured fit to the VM (freshness-checked there) so the
    // exact measured plan can be frozen for publish. Never reported during
    // composition: this runs after a successful measured plan exists.
    LaunchedEffect(plan) {
        if (plan is GeneratorEditorialRows.PlanResult.Planned) {
            onMeasured(plan.expression)
        }
    }
}
