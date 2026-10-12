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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
    contentWrapper: @Composable (CanvasParcel, NavParcel) -> Unit = { canvas, nav ->
        nav()
        canvas()
    },
) {
    val measurer = rememberCollageTextMeasurer()
    var images by remember(sources) { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    val noFitCopy = stringResource(R.string.create_no_fitting_expression)
    val preparingCopy = stringResource(R.string.create_preparing_expressions)
    val noteArg = note?.takeIf { it.isNotBlank() }
    val currentOnPrepared by rememberUpdatedState(onPrepared)

    LaunchedEffect(session, session.draftGeneration, input, sources, note, track, cropSource, artwork, artworkBitmap, measurer) {
        val generation = session.beginPreparing()
        try {
            val decoded = images.ifEmpty { withContext(Dispatchers.Default) { decodeSources(sources) } }
            images = decoded
            val sealed = candidateBuilder?.invoke(measurer)
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
            if (!session.isCurrentPreparation(generation)) return@LaunchedEffect
            if (drawable.isEmpty()) {
                session.showNotice(noFitCopy)
            } else {
                // A rejected permutation cannot consume its grammar/style's
                // single slot: deduplicate only after actual renderer prepare.
                val variants = session.arrangePreparedCandidates(drawable)
                if (BuildConfig.DEBUG) Log.d("CollagePreparation", "uniqueVariants=${variants.size}")
                session.replaceCandidates(variants)
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
            if (session.isCurrentPreparation(generation)) session.showNotice(noFitCopy)
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
    LaunchedEffect(displayed, prepared, prepareInputs, session.buildGeneration) {
        if (displayed != null && prepareInputs != null) {
            currentOnPrepared(displayed.candidateId, prepared is CollageRenderer.SceneResult.Ready, prepareInputs)
        }
    }

    Column(modifier.fillMaxWidth()) {
        if (input.note?.isNotEmpty() == true) Ber1NoteMeasureReporter(input, onMeasured)
        contentWrapper(
            {
                val result = prepared
                if (displayed != null && result is CollageRenderer.SceneResult.Ready) {
                    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val ratio = CollageRenderer.CANVAS_WIDTH.toFloat() / CollageRenderer.CANVAS_HEIGHT
                        val width = if (maxHeight != Dp.Infinity) minOf(maxWidth, maxHeight * ratio) else maxWidth
                        val canvas = Modifier.size(width, width / ratio)
                        if (onSlotTap == null) {
                            CollageCanvas(result.scene, images, "collage candidate", canvas, measurer, artworkBitmap,
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
