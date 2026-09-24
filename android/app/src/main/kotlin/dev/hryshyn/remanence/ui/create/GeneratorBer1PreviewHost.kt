package dev.hryshyn.remanence.ui.create

import android.graphics.BitmapFactory
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import dev.hryshyn.remanence.core.model.GeneratorEditorialRows
import dev.hryshyn.remanence.core.model.GeneratorExpression

/**
 * Stage1-C Create CONTENT preview host. Renders the BER1 typed preview state
 * with a visible fallback for every non-[GeneratorPreviewState.Ready] case —
 * never a blank or partial postcard. Photos resolve via the pure planner; a
 * non-empty note is measured on-device here (bundled Hold Sans, fixed
 * NOTE_REGION) before any expression is accepted, then drawn with the same
 * single layout. Not wired to any route, publish path, or transport.
 */
@Composable
fun GeneratorBer1PreviewHost(
    state: GeneratorPreviewState,
    modifier: Modifier = Modifier,
    /**
     * ADR-018 measured-note report: invoked ONLY when the host has measured a
     * non-empty note on-device and produced a real fitted BER1 plan. The VM
     * freshness-checks the reported input before freezing it for publish.
     */
    onMeasured: (GeneratorExpression.ResolvedExpression) -> Unit = {},
) {
    when (state) {
        GeneratorPreviewState.Idle -> PreviewNotice("Select 3–5 photos to preview.", modifier)
        GeneratorPreviewState.Loading -> PreviewNotice("Preparing preview…", modifier)
        is GeneratorPreviewState.Rejected ->
            PreviewNotice("Preview unavailable: ${state.reasons.joinToString("; ")}", modifier)
        is GeneratorPreviewState.Incompatible ->
            PreviewNotice("This photo mix can't be previewed: ${state.reason}", modifier)
        is GeneratorPreviewState.Unavailable ->
            PreviewNotice("Preview unavailable: ${state.reason}", modifier)
        is GeneratorPreviewState.Ready ->
            PreviewExpression(state.expression, state.sources, modifier)
        is GeneratorPreviewState.NotePending ->
            NotePendingPreview(state, modifier, onMeasured)
    }
}

@Composable
private fun PreviewNotice(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.testTag("generator_preview_notice"),
    )
}

@Composable
private fun PreviewExpression(
    expression: GeneratorExpression.ResolvedExpression,
    sources: List<GeneratorPreviewSource>,
    modifier: Modifier = Modifier,
) {
    val bitmaps = rememberPreviewBitmaps(sources)
    val result = GeneratorBer1Preview(
        expression = expression,
        sources = bitmaps,
        modifier = modifier.testTag("generator_preview_canvas"),
    )
    if (result !is GeneratorBer1Renderer.SceneResult.Ready) {
        PreviewNotice("Preview blocked: ${describe(result)}")
    }
}

@Composable
private fun NotePendingPreview(
    state: GeneratorPreviewState.NotePending,
    modifier: Modifier = Modifier,
    onMeasured: (GeneratorExpression.ResolvedExpression) -> Unit,
) {
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
    val plan = remember(state.input, measurement) {
        GeneratorEditorialRows.plan(state.input, measurement)
    }
    // Report the real measured fit to the VM (freshness-checked there) so the
    // exact measured plan can be frozen for publish. Never reported during
    // composition: this runs after a successful measured plan exists.
    LaunchedEffect(plan) {
        if (plan is GeneratorEditorialRows.PlanResult.Planned) {
            onMeasured(plan.expression)
        }
    }
    when (plan) {
        is GeneratorEditorialRows.PlanResult.Planned ->
            PreviewExpression(plan.expression, state.sources, modifier)
        is GeneratorEditorialRows.PlanResult.Incompatible ->
            PreviewNotice("Preview unavailable: ${plan.reason}", modifier)
        is GeneratorEditorialRows.PlanResult.Invalid ->
            PreviewNotice("Preview unavailable: ${plan.reasons.joinToString("; ")}", modifier)
        is GeneratorEditorialRows.PlanResult.NeedsNoteMeasure ->
            PreviewNotice("Preview pending note measurement.", modifier)
    }
}

@Composable
private fun rememberPreviewBitmaps(
    sources: List<GeneratorPreviewSource>,
): Map<String, ImageBitmap> = remember(sources) {
    val bitmaps = LinkedHashMap<String, ImageBitmap>(sources.size)
    sources.forEach { source ->
        val bitmap = BitmapFactory.decodeByteArray(source.jpegBytes, 0, source.jpegBytes.size)
        if (bitmap != null) bitmaps[source.contentId] = bitmap.asImageBitmap()
    }
    bitmaps
}

private fun describe(result: GeneratorBer1Renderer.SceneResult): String = when (result) {
    is GeneratorBer1Renderer.SceneResult.NeedsNoteMeasure -> "note needs measurement"
    is GeneratorBer1Renderer.SceneResult.NoteRenderBlocked -> result.reason
    is GeneratorBer1Renderer.SceneResult.Incompatible -> result.reason
    is GeneratorBer1Renderer.SceneResult.Rejected -> result.reasons.joinToString("; ")
    is GeneratorBer1Renderer.SceneResult.Ready -> "ready"
}
