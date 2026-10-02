package dev.hryshyn.remanence.ui.create

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.CollageCanvas
import dev.hryshyn.remanence.ui.collage.CollageCandidateProvider
import dev.hryshyn.remanence.ui.collage.CollageCropSource
import dev.hryshyn.remanence.ui.collage.CollageProviderResult
import dev.hryshyn.remanence.ui.collage.CollageRejection
import dev.hryshyn.remanence.ui.collage.CollageRenderer
import dev.hryshyn.remanence.ui.collage.rememberCollageTextMeasurer
import dev.hryshyn.remanence.ui.hold.HoldTextButton

/**
 * Sender collage chooser/preview host. Builds the grammar-5 candidates once
 * per (input, note, track, analysis) and renders the DISPLAYED candidate
 * (browse cursor, first visible as soon as ready) through the SAME
 * [CollageRenderer.prepare] + [CollageCanvas] the recipient uses — so sender
 * and receiver scenes match by construction. There is no white-grid
 * fallback: an unavailable analysis or a failed prepare renders typed copy,
 * never a fabricated layout.
 *
 * Display is separate from freezing: browsing shows a candidate without
 * authorising anything. The [onSelect] action is enabled while a candidate
 * is displayed and freezes exactly that candidate; callers select then
 * freeze against the same input object this host built from.
 * Previous/Next move the display cursor (from an empty cursor they start at
 * the first candidate), clear any explicit highlight, and always invoke
 * [onNavigate] first, so navigation invalidates an older freeze instead of
 * silently showing a different sheet under a frozen id. [frozenCandidateId]
 * is read fresh on every recomposition (the host already recomposes when the
 * display moves), so no key-reset may dispose this subtree: that would
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
    artwork: GeneratorExpression.ArtworkRef? = null,
    /**
     * T144: verified thumbnail cover bitmap decoded from the resolved bytes.
     * Forwarded to the shared renderer/canvas so the frozen sender candidate
     * shows the real cover; null preserves the text band. Never persisted.
     */
    artworkBitmap: ImageBitmap? = null,
    frozenCandidateId: () -> String? = { null },
    onSelect: (Int) -> Unit = {},
    onNavigate: () -> Unit = {},
    onMeasured: (GeneratorExpression.ResolvedExpression) -> Unit = {},
    /**
     * T150 photo hotspots (Astra `render.mjs:11` capsule-slot buttons):
     * tapping a photograph opens the crop stage for its content id.
     * Null renders the canvas alone (content form preview).
     */
    onSlotTap: ((String) -> Unit)? = null,
    /**
     * T150 integration: sealed all-family V4 candidates from the producer +
     * `AllFamilyV4Adapter.seal` (T151 stage 7 on the committed T154 schema).
     * Returning `null`/empty keeps the V3 provider path — never a fake
     * candidate.
     */
    candidateBuilder: (suspend () -> List<dev.hryshyn.remanence.ui.collage.CollageCandidate>?)? = null,
) {
    val measurer = rememberCollageTextMeasurer()
    LaunchedEffect(input, note, track, cropSource, artwork) {
        val sealed = try {
            candidateBuilder?.invoke()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Fail-safe: an unsealed build falls back to the V3 provider path
            // (typed notice when even that is unavailable).
            null
        }
        if (!sealed.isNullOrEmpty()) {
            session.replaceCandidates(sealed)
            return@LaunchedEffect
        }
        when (
            val result = CollageCandidateProvider(cropSource, measurer)
                .candidates(input, note, track, artwork)
        ) {
            is CollageProviderResult.Candidates -> session.replaceCandidates(result.candidates)
            is CollageProviderResult.Unavailable -> session.showNotice(result.reason)
            is CollageProviderResult.Rejected -> {
                session.showNotice(result.reason)
                result.rejection?.let(::reportCollageRejection)
            }
        }
    }

    Column(modifier.fillMaxWidth()) {
        if (input.note?.isNotEmpty() == true) {
            // Note publication still resolves through the BER1 measured plan;
            // report it without rendering the BER1 preview.
            Ber1NoteMeasureReporter(input, onMeasured)
        }
        if (session.candidates.isNotEmpty()) {
            // Browse vs selected frame (frozen `.expression-nav` /
            // `.selected-label`): browse shows the N/total counter between
            // the buttons; once explicitly selected, the counter is replaced
            // by the selected label while Previous/Next stay as the
            // browse-again affordance. Compact, outside the canvas.
            if (session.selected != null) {
                Text(
                    "✓ selected expression",
                    modifier = Modifier.testTag("collage_selected_label"),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (session.candidates.size > 1) {
                    HoldTextButton(
                        onClick = {
                            session.previous()
                            onNavigate()
                        },
                        modifier = Modifier.testTag("collage_previous"),
                    ) { Text("Previous") }
                    if (session.selected == null) {
                        Text(
                            "${session.displayIndex + 1} / ${session.candidates.size}",
                            modifier = Modifier.testTag("collage_nav_counter"),
                        )
                    }
                    HoldTextButton(
                        onClick = {
                            session.next()
                            onNavigate()
                        },
                        modifier = Modifier.testTag("collage_next"),
                    ) { Text("Next") }
                }
                Button(
                    onClick = { onSelect(session.displayIndex) },
                    enabled = session.displayed != null,
                    modifier = Modifier.testTag("collage_select"),
                ) { Text("Select") }
            }
        }
        if (frozenCandidateId() != null) {
            // The frozen candidate hash is internal identity, not product copy:
            // keep only a zero-cost marker so selection can be asserted without
            // showing a raw `ber1-…` string.
            Box(Modifier.testTag("collage_frozen").size(1.dp))
        }

        val displayed = session.displayed
        when {
            session.notice != null ->
                Text(session.notice!!, modifier = Modifier.testTag("collage_notice"))

            displayed == null ->
                Text("Select a collage", modifier = Modifier.testTag("collage_select_hint"))

            else -> {
                val images = remember(displayed, sources) { decodeSources(sources) }
                val noteArg = note?.takeIf { it.isNotBlank() }
                val sizes = images.mapValues { CollageRenderer.SourcePhoto(it.value.width, it.value.height) }
                val prepared = remember(displayed, images, noteArg, track, measurer, artworkBitmap) {
                    CollageRenderer.prepare(
                        displayed.expression,
                        noteArg,
                        track,
                        sizes,
                        measurer,
                        artwork = artworkBitmap?.let {
                            CollageRenderer.SourcePhoto(it.width, it.height)
                        },
                    )
                }
                when (prepared) {
                    is CollageRenderer.SceneResult.Ready -> {
                        // Whole composition fits the available bounded height
                        // (frozen 9:16 canvas), centred; controls stay outside.
                        // Unbounded callers (legacy scroll) keep full width.
                        val ratio = CollageRenderer.CANVAS_WIDTH.toFloat() /
                            CollageRenderer.CANVAS_HEIGHT.toFloat()
                        BoxWithConstraints(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            val maxW = maxWidth
                            val maxH: Dp = maxHeight
                            val side = if (maxH != Dp.Infinity) {
                                minOf(maxW, maxH * ratio)
                            } else {
                                maxW
                            }
                            val canvasModifier = Modifier.size(side, side / ratio)
                            val tap = onSlotTap
                            if (tap == null) {
                                CollageCanvas(
                                    scene = prepared.scene,
                                    images = images,
                                    contentDescription = "collage candidate",
                                    modifier = canvasModifier,
                                    textMeasurer = measurer,
                                    artwork = artworkBitmap,
                                )
                            } else {
                                CollageHotspotCanvas(
                                    scene = prepared.scene,
                                    images = images,
                                    placements = displayed.expression.placements,
                                    onSlotTap = tap,
                                    modifier = canvasModifier,
                                    textMeasurer = measurer,
                                    artwork = artworkBitmap,
                                )
                            }
                        }
                    }

                    is CollageRenderer.SceneResult.Rejected ->
                        Text(prepared.reason, modifier = Modifier.testTag("collage_preview_blocked"))
                }
            }
        }
    }
}

/**
 * Bounded DEBUG evidence for the next immutable Android reproduction: the
 * exact failed style/photo/slot and dominant frozen cause. It logs no bytes
 * and never changes the displayed rejection copy.
 */
private fun reportCollageRejection(rejection: CollageRejection) {
    if (BuildConfig.DEBUG) {
        Log.d("CollageRejection", rejection.safeSummary())
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
