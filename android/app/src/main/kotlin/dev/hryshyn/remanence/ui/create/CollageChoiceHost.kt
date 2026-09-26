package dev.hryshyn.remanence.ui.create

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.CollageCanvas
import dev.hryshyn.remanence.ui.collage.CollageCandidateProvider
import dev.hryshyn.remanence.ui.collage.CollageCropSource
import dev.hryshyn.remanence.ui.collage.CollageProviderResult
import dev.hryshyn.remanence.ui.collage.CollageRenderer
import dev.hryshyn.remanence.ui.collage.rememberCollageTextMeasurer
import dev.hryshyn.remanence.ui.hold.HoldTextButton

/**
 * Sender collage chooser/preview host. Builds the grammar-5 candidates once
 * per (input, note, track, analysis) and renders ONLY the explicitly selected
 * candidate through the SAME [CollageRenderer.prepare] + [CollageCanvas] the
 * recipient uses — so sender and receiver scenes match by construction. There
 * is no white-grid fallback: an unavailable analysis or a failed prepare
 * renders typed copy, never a fabricated layout.
 *
 * Selection is explicit-only: nothing auto-selects. The [onSelect] action is
 * enabled only while a candidate is highlighted and freezes exactly that
 * displayed candidate; callers select then freeze against the same input
 * object this host built from. Previous/Next highlight a candidate (from an
 * unhighlighted list they start at an end) and always invoke [onNavigate]
 * first, so navigation invalidates an older freeze instead of silently
 * showing a different sheet under a frozen id. [frozenCandidateId] is read
 * fresh on every recomposition (the host already recomposes when the
 * selection moves), so no key-reset may dispose this subtree: that would
 * relaunch the provider effect and wipe the fresh selection. For
 * note-carrying inputs the shared
 * BER1 note reporter keeps note publication byte-identical without rendering
 * the BER1 preview.
 *
 * Publish is intentionally not wired here.
 */
@Composable
internal fun CollageChoiceHost(
    session: CollageChoiceSession,
    input: GeneratorExpression.GeneratorInput,
    sources: List<GeneratorPreviewSource>,
    note: String?,
    track: CapsuleTrackSnapshotV1?,
    cropSource: CollageCropSource,
    modifier: Modifier = Modifier,
    frozenCandidateId: () -> String? = { null },
    onSelect: (Int) -> Unit = {},
    onNavigate: () -> Unit = {},
    onMeasured: (GeneratorExpression.ResolvedExpression) -> Unit = {},
) {
    val measurer = rememberCollageTextMeasurer()
    LaunchedEffect(input, note, track, cropSource) {
        when (val result = CollageCandidateProvider(cropSource, measurer).candidates(input, note, track)) {
            is CollageProviderResult.Candidates -> session.replaceCandidates(result.candidates)
            is CollageProviderResult.Unavailable -> session.showNotice(result.reason)
            is CollageProviderResult.Rejected -> session.showNotice(result.reason)
        }
    }

    Column(modifier.fillMaxWidth()) {
        if (input.note?.isNotEmpty() == true) {
            // Note publication still resolves through the BER1 measured plan;
            // report it without rendering the BER1 preview.
            Ber1NoteMeasureReporter(input, onMeasured)
        }
        if (session.candidates.size > 1) {
            HoldTextButton(
                onClick = {
                    if (session.selectedIndex == null) {
                        session.select(session.candidates.lastIndex)
                    } else {
                        session.previous()
                    }
                    onNavigate()
                },
                modifier = Modifier.testTag("collage_previous"),
            ) { Text("Previous") }
            HoldTextButton(
                onClick = {
                    if (session.selectedIndex == null) {
                        session.select(0)
                    } else {
                        session.next()
                    }
                    onNavigate()
                },
                modifier = Modifier.testTag("collage_next"),
            ) { Text("Next") }
        }

        if (session.candidates.isNotEmpty()) {
            Button(
                onClick = { onSelect(session.selectedIndex ?: return@Button) },
                enabled = session.selectedIndex != null,
                modifier = Modifier.testTag("collage_select"),
            ) { Text("Select") }
        }
        val frozenId = frozenCandidateId()
        if (frozenId != null) {
            Text(frozenId, modifier = Modifier.testTag("collage_frozen"))
        }

        val selected = session.selected
        when {
            session.notice != null ->
                Text(session.notice!!, modifier = Modifier.testTag("collage_notice"))

            selected == null ->
                Text("Select a collage", modifier = Modifier.testTag("collage_select_hint"))

            else -> {
                val images = remember(selected, sources) { decodeSources(sources) }
                val noteArg = note?.takeIf { it.isNotBlank() }
                val sizes = images.mapValues { CollageRenderer.SourcePhoto(it.value.width, it.value.height) }
                val prepared = remember(selected, images, noteArg, track, measurer) {
                    CollageRenderer.prepare(selected.expression, noteArg, track, sizes, measurer)
                }
                when (prepared) {
                    is CollageRenderer.SceneResult.Ready -> CollageCanvas(
                        scene = prepared.scene,
                        images = images,
                        contentDescription = "collage candidate",
                        modifier = Modifier.fillMaxWidth(),
                        textMeasurer = measurer,
                    )

                    is CollageRenderer.SceneResult.Rejected ->
                        Text(prepared.reason, modifier = Modifier.testTag("collage_preview_blocked"))
                }
            }
        }
    }
}

private fun decodeSources(sources: List<GeneratorPreviewSource>): Map<String, ImageBitmap> {
    val out = LinkedHashMap<String, ImageBitmap>(sources.size)
    sources.forEach { source ->
        val bitmap = BitmapFactory.decodeByteArray(source.jpegBytes, 0, source.jpegBytes.size)
        if (bitmap != null) out[source.contentId] = bitmap.asImageBitmap()
    }
    return out
}
