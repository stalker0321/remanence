package dev.hryshyn.remanence.ui.create

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.*
import dev.hryshyn.remanence.ui.hold.HoldButton
import dev.hryshyn.remanence.ui.hold.HoldTextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Identity of the actual content validated by the shared renderer. */
internal data class PrepareInputs(
    val note: String?,
    val trackId: String?,
    val artworkSize: String?,
    val artwork: GeneratorExpression.ArtworkRef? = null,
)

/**
 * In-memory identity of the cohort's inputs. A fresh host may retain authored
 * geometry only for this exact draft/content/source lifetime. Font/layout
 * readiness is deliberately NOT retained: every host runs renderer prepare.
 */
internal data class CollagePreparationInputs(
    val input: GeneratorExpression.GeneratorInput,
    val sources: List<GeneratorPreviewSource>,
    val note: String?,
    val track: CapsuleTrackSnapshotV1?,
    val cropSource: CollageCropSource,
    val artwork: GeneratorExpression.ArtworkRef?,
    val artworkBitmap: ImageBitmap?,
)

/**
 * Kept mounted while editing content and browsing. All candidates pass the
 * recipient's real text/layout prepare before entering the browse list. The
 * content screen can withhold the parcels while this work is in progress.
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
    artworkBitmap: ImageBitmap? = null,
    frozenCandidateId: () -> String? = { null },
    onSelect: (Int) -> Unit = {},
    onNavigate: () -> Unit = {},
    onMeasured: (GeneratorExpression.ResolvedExpression) -> Unit = {},
    onPrepared: (String, Boolean, PrepareInputs) -> Unit = { _, _, _ -> },
    prepareInputs: PrepareInputs? = null,
    onSlotTap: ((String) -> Unit)? = null,
    onMusicClick: (() -> Unit)? = null,
    musicActionLabel: String? = null,
    candidateBuilder: (suspend (CollageTextMeasurer) -> List<CollageCandidate>?)? = null,
    showSelectInNavigation: Boolean = true,
    onShown: (Int) -> Unit = {},
    contentWrapper: @Composable (CanvasParcel, NavParcel) -> Unit = { canvas, nav ->
        nav()
        canvas()
    },
) {
    val measurer = rememberCollageTextMeasurer()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnShown by rememberUpdatedState(onShown)
    var images by remember(sources) { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    val noFitCopy = stringResource(R.string.create_no_fitting_expression)
    val preparingCopy = stringResource(R.string.create_preparing_expressions)
    val noteArg = note?.takeIf { it.isNotBlank() }
    val currentOnPrepared by rememberUpdatedState(onPrepared)
    val preparationInputs = CollagePreparationInputs(input, sources, note, track, cropSource, artwork, artworkBitmap)
    val host = remember(session) { Any() }
    DisposableEffect(session, preparationInputs, measurer) {
        session.attachHost(host)
        onDispose { session.detachHost(host) }
    }

    LaunchedEffect(session, session.draftGeneration, input, sources, note, track, cropSource, artwork, artworkBitmap, measurer) {
        val retained = session.retainedCandidates(preparationInputs)
        val generation = session.beginPreparing(retainCandidates = retained != null)
        try {
            val decoded = images.ifEmpty { withContext(Dispatchers.Default) { decodeSources(sources) } }
            images = decoded
            val sealed = retained ?: candidateBuilder?.invoke(measurer)
            val candidates = if (!sealed.isNullOrEmpty()) sealed else {
                when (val result = CollageCandidateProvider(cropSource, measurer)
                    .candidates(input, note, track, artwork)) {
                    is CollageProviderResult.Candidates -> result.candidates
                    else -> {
                        if (BuildConfig.DEBUG) Log.d("CollagePreparation", "provider=${result::class.simpleName}")
                        emptyList()
                    }
                }
            }
            val sizes = decoded.mapValues { CollageRenderer.SourcePhoto(it.value.width, it.value.height) }
            val cover = artworkBitmap?.let { CollageRenderer.SourcePhoto(it.width, it.height) }
            val drawable = candidates.filter { candidate ->
                kotlinx.coroutines.yield()
                val result = CollageRenderer.prepare(candidate.expression, noteArg, track, sizes, measurer, artwork = cover)
                if (BuildConfig.DEBUG && result is CollageRenderer.SceneResult.Rejected) {
                    Log.d("CollagePreparation", "renderer=${result.reason}")
                }
                result is CollageRenderer.SceneResult.Ready
            }
            if (BuildConfig.DEBUG) Log.d("CollagePreparation", "built=${candidates.size} drawable=${drawable.size} decoded=${decoded.size}")
            ensureActive()
            if (!session.isCurrentHost(host) || !session.isCurrentPreparation(generation)) return@LaunchedEffect
            if (drawable.isEmpty()) {
                session.showNotice(noFitCopy)
            } else {
                // A rejected permutation cannot consume its grammar/style's
                // single slot: deduplicate only after actual renderer prepare.
                val variants = if (retained != null && drawable.size == retained.size) retained
                    else session.arrangePreparedCandidates(drawable)
                if (BuildConfig.DEBUG) Log.d("CollagePreparation", "uniqueVariants=${variants.size}")
                // Revalidation of unchanged inputs must not replace the
                // cohort: that would erase its cursor, selection and crops.
                if (variants !== retained) session.replaceCandidates(variants)
                session.finishPreparing(preparationInputs)
                // Replacement invalidates prior proofs. Report the new proofs
                // afterwards, in the same uninterrupted main turn.
                prepareInputs?.let { proof ->
                    variants.forEach { currentOnPrepared(it.candidateId, true, proof) }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (BuildConfig.DEBUG) Log.d("CollagePreparation", "failure=${error::class.simpleName}")
            if (session.isCurrentHost(host) && session.isCurrentPreparation(generation)) session.showNotice(noFitCopy)
        }
    }

    // Crop edits replace one expression without rebuilding the candidate set.
    val displayed = session.displayed
    val prepared = remember(displayed, images, noteArg, track, artworkBitmap, measurer) {
        displayed?.let {
            CollageRenderer.prepare(
                it.expression, noteArg, track,
                images.mapValues { image -> CollageRenderer.SourcePhoto(image.value.width, image.value.height) },
                measurer,
                artwork = artworkBitmap?.let { image -> CollageRenderer.SourcePhoto(image.width, image.height) },
            )
        }
    }
    LaunchedEffect(displayed, prepared, prepareInputs, session.buildGeneration, session.isPreparing) {
        // Empty images during remount are not a renderer rejection of the
        // authored sheet. Wait for this host's complete validation pass.
        if (displayed != null && prepareInputs != null &&
            session.isCurrentHost(host) && session.isPreparedFor(preparationInputs)) {
            currentOnPrepared(displayed.candidateId, prepared is CollageRenderer.SceneResult.Ready, prepareInputs)
        }
    }

    Column(modifier.fillMaxWidth()) {
        if (input.note?.isNotEmpty() == true) Ber1NoteMeasureReporter(input, onMeasured)
        contentWrapper(
            {
                val result = prepared
                if (displayed != null && result is CollageRenderer.SceneResult.Ready) {
                    val renderedIndex = session.displayIndex
                    val renderedBuild = session.buildGeneration
                    var inWindow by remember(renderedBuild, renderedIndex) { mutableStateOf(false) }
                    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val ratio = CollageRenderer.CANVAS_WIDTH.toFloat() / CollageRenderer.CANVAS_HEIGHT
                        val width = if (maxHeight != Dp.Infinity) minOf(maxWidth, maxHeight * ratio) else maxWidth
                        val canvas = Modifier.size(width, width / ratio).onGloballyPositioned { coordinates ->
                            val bounds = coordinates.boundsInWindow()
                            inWindow = coordinates.isAttached && bounds.width > 0 && bounds.height > 0
                        }.drawWithContent {
                            drawContent()
                            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                                inWindow && size.width > 0 && size.height > 0 && session.buildGeneration == renderedBuild &&
                                session.displayIndex == renderedIndex && session.displayed?.candidateId == displayed.candidateId) {
                                runCatching { currentOnShown(renderedIndex) }
                            }
                        }
                        if (onSlotTap == null) {
                            CollageCanvas(result.scene, images, stringResource(R.string.media_collage_description), canvas, measurer, artworkBitmap,
                                onMusicClick = onMusicClick, musicActionLabel = musicActionLabel)
                        } else {
                            CollageHotspotCanvas(result.scene, images, displayed.expression.placements,
                                onSlotTap, canvas, measurer, artworkBitmap,
                                onMusicClick = onMusicClick, musicActionLabel = musicActionLabel)
                        }
                    }
                } else {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        // Admission diagnostics remain in session state only;
                        // never expose internal English reasons as UI copy.
                        Text(if (session.isPreparing) preparingCopy else noFitCopy,
                            modifier = Modifier.testTag("collage_notice"))
                    }
                }
            },
            {
                if (session.candidates.isNotEmpty()) {
                    CollageBrowseNav(session, onSelect, onNavigate,
                        prepared is CollageRenderer.SceneResult.Ready,
                        showSelect = showSelectInNavigation)
                }
            },
        )
        if (frozenCandidateId() != null) Box(Modifier.testTag("collage_frozen").size(0.dp))
    }
}

internal typealias CanvasParcel = @Composable () -> Unit
internal typealias NavParcel = @Composable () -> Unit

@Composable
internal fun CollageBrowseNav(
    session: CollageChoiceSession,
    onSelect: (Int) -> Unit,
    onNavigate: () -> Unit,
    preparedReady: Boolean,
    modifier: Modifier = Modifier,
    showSelect: Boolean = true,
) {
    val previousLabel = stringResource(R.string.create_previous_expression)
    val nextLabel = stringResource(R.string.create_next_expression)
    val selected = !showSelect && session.selectedIndex != null
    val counter = "${session.displayIndex + 1} / ${session.candidates.size}" +
        if (selected) " · ✓ ${stringResource(R.string.create_selected_expression)}" else ""
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically) {
        HoldTextButton(
            onClick = { session.previous(); onNavigate() },
            enabled = session.displayIndex > 0,
            modifier = Modifier.testTag("collage_previous").semantics { contentDescription = previousLabel },
        ) { Text("←", style = MaterialTheme.typography.titleLarge) }
        Box(if (selected) Modifier.testTag("collage_selected_label") else Modifier) {
            Text(counter, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("collage_nav_counter"))
        }
        HoldTextButton(
            onClick = { session.next(); onNavigate() },
            enabled = session.displayIndex < session.candidates.lastIndex,
            modifier = Modifier.testTag("collage_next").semantics { contentDescription = nextLabel },
        ) { Text("→", style = MaterialTheme.typography.titleLarge) }
        if (showSelect) HoldButton(
            onClick = { onSelect(session.displayIndex) },
            enabled = session.displayed != null && preparedReady,
            modifier = Modifier.testTag("collage_select"),
        ) { Text(stringResource(R.string.create_this_one)) }
    }
}

private fun decodeSources(sources: List<GeneratorPreviewSource>): Map<String, ImageBitmap> =
    sources.mapNotNull { source ->
        BitmapFactory.decodeByteArray(source.jpegBytes, 0, source.jpegBytes.size)?.asImageBitmap()
            ?.let { source.contentId to it }
    }.toMap()
