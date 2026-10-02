package dev.hryshyn.remanence.ui.create

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
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
 * Real-prepare drawable inputs the host measured (pass-through bundle).
 * The VM supplies the current value as [prepareInputs] and the effect
 * echoes it back in [onPrepared]; equality against live state at every
 * gate is what makes stale reports harmless. Crop transforms ride OUTSIDE
 * this key on purpose: sealed crop windows change photo source rects only
 * and never enter text measurement (regions, types, note, track, artwork
 * are identical), so a crop edit cannot change a drawable verdict; cross-
 * candidate correction misuse stays prevented by the keyed store and the
 * stage-close checks. Artwork is a dimension marker (bytes never enter
 * identity here, matching the sealed contract).
 */
internal data class PrepareInputs(
    val note: String?,
    val trackId: String?,
    val artworkSize: String?,
)

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
     * Lifecycle-safe report of the measured prepare for the displayed
     * candidate: (candidateId, ready, measured inputs). The VM accepts the
     * inputs only on equality with live state at every gate, so stale
     * reports can never approve another candidate. Fires from an effect,
     * never during composition.
     */
    onPrepared: (candidateId: String, ready: Boolean, inputs: PrepareInputs) -> Unit = { _, _, _ -> },
    /**
     * The drawable inputs the VM computed for the current state; echoed
     * back in [onPrepared]. Null disables reporting (preview-only hosts).
     */
    prepareInputs: PrepareInputs? = null,
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
    /**
     * Frozen sibling layout hook (render.mjs expression case: art-space
     * THEN expression-nav as siblings). The host always invokes it exactly
     * once with the canvas parcel and the nav row; the caller arranges
     * them. Default preserves the historical nav-then-canvas order for
     * existing callers. Single host mount and single build effect either
     * way — this only rearranges emitted subtrees, never session, prepare
     * reporting, or effects.
     */
    contentWrapper: @Composable (CanvasParcel, NavParcel) -> Unit = { canvasContent, navContent ->
        navContent()
        canvasContent()
    },
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
        // The displayed candidate's prepare is computed BEFORE the controls
        // so Select is enabled only on a positively-Ready sheet (never on
        // unknown or rejected), and so the drawable proof effect below
        // reports exactly what the user sees.
        val displayed = session.displayed
        val images = remember(displayed, sources) { decodeSources(sources) }
        val noteArg = note?.takeIf { it.isNotBlank() }
        val prepared = remember(displayed, images, noteArg, track, measurer, artworkBitmap) {
            displayed?.let { shown ->
                val sizes = images.mapValues { CollageRenderer.SourcePhoto(it.value.width, it.value.height) }
                CollageRenderer.prepare(
                    shown.expression,
                    noteArg,
                    track,
                    sizes,
                    measurer,
                    artwork = artworkBitmap?.let {
                        CollageRenderer.SourcePhoto(it.width, it.height)
                    },
                )
            }
        }
        val preparedReady = prepared is CollageRenderer.SceneResult.Ready
        val reportedInputs = prepareInputs
        if (displayed != null && reportedInputs != null) {
            // Effect, not render: reports the measured outcome tied to the
            // exact prepared inputs; the VM equality-checks them on arrival.
            LaunchedEffect(displayed.candidateId, displayed.expression, noteArg, track, artworkBitmap, reportedInputs) {
                onPrepared(displayed.candidateId, preparedReady, reportedInputs)
            }
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
        }
        contentWrapper(
            {
                when {
                    session.notice != null ->
                        Text(session.notice!!, modifier = Modifier.testTag("collage_notice"))

                    displayed == null ->
                        Text("Select a collage", modifier = Modifier.testTag("collage_select_hint"))

                    else -> {
                val sizes = images.mapValues { CollageRenderer.SourcePhoto(it.value.width, it.value.height) }
                when (val result = prepared) {
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
                                    scene = result.scene,
                                    images = images,
                                    contentDescription = "collage candidate",
                                    modifier = canvasModifier,
                                    textMeasurer = measurer,
                                    artwork = artworkBitmap,
                                )
                            } else {
                                CollageHotspotCanvas(
                                    scene = result.scene,
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
                        Text(result.reason, modifier = Modifier.testTag("collage_preview_blocked"))

                    // Unreachable: this arm requires a displayed candidate,
                    // for which a prepare result always exists.
                    null -> Unit
                }
            }
        }
        },
            {
                if (session.candidates.isNotEmpty()) {
                    CollageBrowseNav(
                        session = session,
                        onSelect = onSelect,
                        onNavigate = onNavigate,
                        preparedReady = preparedReady,
                    )
                }
            },
        )
        if (frozenCandidateId() != null) {
            // The frozen candidate hash is internal identity, not product copy:
            // keep only a zero-cost marker so selection can be asserted without
            // showing a raw `ber1-…` string.
            Box(Modifier.testTag("collage_frozen").size(1.dp))
        }
    }
}

/**
 * Canvas parcel / nav row content slots for [CollageChoiceHost]'s
 * [contentWrapper] hook.
 */
internal typealias CanvasParcel = @Composable () -> Unit
internal typealias NavParcel = @Composable () -> Unit

/**
 * Frozen sibling expression-nav (render.mjs expression case): Previous /
 * counter / Next + Select with unchanged copy, order and actions.
 *
 * Native overflow adaptation (frozen buttons stay min-48 and never
 * shrink/wrap: handoff buttons are flex-shrink:0): natural widths are
 * measured first and the row scrolls horizontally ONLY when they exceed
 * the viewport (photo-row pattern). A fitting row stays a plain Row, so
 * taps behave exactly as before — a scrollable with nothing to scroll
 * swallowed taps in test. Labels stay one line; min-48 targets unchanged.
 */
@Composable
internal fun CollageBrowseNav(
    session: CollageChoiceSession,
    onSelect: (Int) -> Unit,
    onNavigate: () -> Unit,
    preparedReady: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        // Start scrolling (safe superset: inner measures natural widths),
        // relax to a plain Row when everything fits. Keyed on structure
        // so shrink/growth re-measures; the guard below prevents feedback
        // once relaxed.
        var overflow by remember(session.candidates.size, session.selected != null) {
            mutableStateOf(true)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (overflow) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.onGloballyPositioned { coords ->
                    if (overflow) {
                        overflow = coords.size.width > maxWidthPx
                    }
                },
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
                    ) { Text("Previous", maxLines = 1, softWrap = false) }
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
                    ) { Text("Next", maxLines = 1, softWrap = false) }
                }
                Button(
                    onClick = { onSelect(session.displayIndex) },
                    enabled = session.displayed != null && preparedReady,
                    modifier = Modifier.testTag("collage_select"),
                ) { Text("Select", maxLines = 1, softWrap = false) }
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
