package dev.hryshyn.remanence.ui.create

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
        if (session.candidates.size > 1) {
            HoldTextButton(
                onClick = { session.previous() },
                modifier = Modifier.testTag("collage_previous"),
            ) { Text("Previous") }
            HoldTextButton(
                onClick = { session.next() },
                modifier = Modifier.testTag("collage_next"),
            ) { Text("Next") }
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
